package com.grdp.studio.waterinvasion;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static com.grdp.studio.waterinvasion.WaterInvasionModels.*;

/** 后端持有任务生命周期，浏览器刷新/关闭不会中止结果落库。 */
@Service
public class WaterInvasionService {
    private final WaterInvasionStorage storage;
    private final WaterInvasionLegacyGateway legacy;
    private final ObjectMapper json;
    private final ExecutorService workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
        r->{var t=new Thread(r,"water-invasion-task");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public WaterInvasionService(WaterInvasionStorage storage,WaterInvasionLegacyGateway legacy,ObjectMapper json){this.storage=storage;this.legacy=legacy;this.json=json;}
    public Task start(Start incoming,String actor,String cookie,boolean importing) {
        double limit=incoming.waterGasRatioLimit()==null?-1:incoming.waterGasRatioLimit();
        if(!Double.isFinite(limit) || (limit!=-1 && limit<=0))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"生产水气比上限应为正数或 -1");
        var request=new Start(incoming.projectId(),incoming.gasReservoirId(),incoming.wellName().trim(),incoming.requestId(),
            incoming.isUseActualStaticPressure()==null || incoming.isUseActualStaticPressure(),limit);
        var created=storage.create(request,actor,importing);
        if(created.created()) {
            try{workers.execute(()->execute(created.task().id(),request,cookie,importing));}
            catch(RejectedExecutionException e){storage.fail(created.task().id(),"后台计算队列已满，请稍后重新发起",false);}
        }
        return storage.task(created.task().id(),new Scope(request.projectId(),request.gasReservoirId(),request.wellName()));
    }
    void execute(long id,Start request,String cookie,boolean importing) {
        var scope=new Scope(request.projectId(),request.gasReservoirId(),request.wellName());
        CompletableFuture<Void> submission=null;
        boolean submitted=false;
        try {
            if(importing){storage.save(id,scope.wellName(),legacy.result(scope,cookie),true);return;}
            var before=legacy.resultIfPresent(scope,cookie);
            String beforeText=before==null?null:json.writeValueAsString(before);
            long started=System.currentTimeMillis();
            var saved=storage.recalculationSnapshot(scope);
            var explicitInput=saved==null?null:WaterInvasionPayload.fromSnapshot(scope,saved,request.waterGasRatioLimit());
            storage.submitted(id);submitted=true;
            submission=explicitInput==null?legacy.start(request,cookie):legacy.calculate(scope.wellName(),explicitInput,cookie);
            long deadline=started+TimeUnit.MINUTES.toMillis(10);
            boolean completed=false;
            while(System.currentTimeMillis()<deadline) {
                // HTTP 成功不是计算完成，但明确的请求失败应立即报告，不能无限等待。
                if(submission.isCompletedExceptionally())submission.join();
                if(WaterInvasionLegacyGateway.completed(legacy.logs(started,cookie),scope,started)){completed=true;break;}
                Thread.sleep(1500);
            }
            if(!completed)throw new TimeoutException();
            // 最终日志可能先于结果可见；短暂重读，但绝不把原封不动的旧返回标为新计算。
            for(int attempt=0;attempt<12;attempt++) {
                var response=legacy.resultIfPresent(scope,cookie);
                if(response!=null && (explicitInput==null ? !json.writeValueAsString(response).equals(beforeText) : WaterInvasionPayload.matches(explicitInput,response)) && submission.isDone() && !submission.isCompletedExceptionally()) {
                    storage.save(id,scope.wellName(),response,false);return;
                }
                Thread.sleep(1000);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"收到完成消息，但尚未确认结果已更新；旧结果保持不变");
        } catch(Exception error) {
            Throwable cause=error instanceof CompletionException && error.getCause()!=null?error.getCause():error;
            boolean timeout=cause instanceof TimeoutException;
            String message=timeout?"未收到可关联本井本次任务的完成日志，未覆盖历史结果":
                cause instanceof ResponseStatusException e?e.getReason():"计算或保存失败，请检查旧平台及新数据库服务";
            if(submitted)storage.failSubmitted(id,message,timeout);else storage.fail(id,message,timeout);
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
        } finally {if(submission!=null && !submission.isDone())submission.cancel(true);}
    }
    @PreDestroy public void close(){workers.shutdownNow();}
    public Task reconcile(long id,Scope scope,String cookie) {
        var task=storage.task(id,scope);
        if(!List.of("FAILED","TIMED_OUT").contains(task.taskStatus()))return task;
        var recovery=storage.recovery(id,scope);
        if(!recovery.blocked() || recovery.startedAt()==null)return task;
        if(!WaterInvasionLegacyGateway.completed(legacy.logs(recovery.startedAt(),cookie),scope,recovery.startedAt()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"尚未找到该井本次计算完成通知，保护未解除");
        var response=legacy.result(scope,cookie);var snapshot=storage.recalculationSnapshot(scope);
        if(snapshot!=null && !WaterInvasionPayload.matches(WaterInvasionPayload.fromSnapshot(scope,snapshot,recovery.request().waterGasRatioLimit()),response))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"返回结果不匹配本地单井输入，保护未解除");
        storage.saveRecovered(id,scope,response);return storage.task(id,scope);
    }
}

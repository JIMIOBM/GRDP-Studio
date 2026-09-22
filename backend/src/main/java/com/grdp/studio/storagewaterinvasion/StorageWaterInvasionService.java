package com.grdp.studio.storagewaterinvasion;

import com.grdp.studio.waterinvasion.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.*;
import static com.grdp.studio.storagewaterinvasion.StorageWaterInvasionModels.*;

/** HTTP 200/{} 不作为成功。必须同时确认本次完成通知、完整输入匹配和五类结果协议。 */
@Service
public class StorageWaterInvasionService {
    private final StorageWaterInvasionStorage storage;
    private final WaterInvasionLegacyGateway legacy;
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
        r->{var t=new Thread(r,"storage-water-invasion");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public StorageWaterInvasionService(StorageWaterInvasionStorage storage,WaterInvasionLegacyGateway legacy){this.storage=storage;this.legacy=legacy;}
    public Task start(Start request,String actor,String cookie) {
        var created=storage.create(request,actor);
        if(created.created())try{workers.execute(()->execute(created.task().id(),request.scope(),cookie));}
        catch(RejectedExecutionException e){storage.fail(created.task().id(),"计算队列已满，请稍后重试",false,false);}
        return storage.task(created.task().id(),request.scope());
    }
    void execute(long id,Scope scope,String cookie) {
        boolean submitted=false;CompletableFuture<Void> response=null;
        try {
            var detail=storage.detail(id,scope);
            var carrier=new WaterInvasionModels.Scope(scope.projectId(),scope.gasReservoirId(),detail.record().carrierWellName());
            // 先记录风险标志，网络中断/服务退出也不能把承载井错当空闲。
            long started=storage.submitted(id);submitted=true;
            response=legacy.calculate(carrier.wellName(),detail.input(),cookie);
            long deadline=started+TimeUnit.MINUTES.toMillis(10);
            while(System.currentTimeMillis()<deadline) {
                if(response.isCompletedExceptionally())response.join();
                if(response.isDone() && collect(id,scope,detail,carrier,started,cookie))return;
                Thread.sleep(1500);
            }
            throw new TimeoutException();
        }catch(Exception error) {
            Throwable cause=error instanceof CompletionException && error.getCause()!=null?error.getCause():error;
            String message=cause instanceof TimeoutException?"未确认本次计算完成，旧平台可能仍在运行；承载井保持锁定，请点击检查计算状态":
                cause instanceof ResponseStatusException e?e.getReason():"调用或保存失败；已提交的承载井保持保护，请检查计算状态";
            storage.fail(id,message,cause instanceof TimeoutException,submitted);
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
        }
        // 不取消 HTTP 后假定旧算法停止。失败状态保留承载井占用，避免迟到结果串入下一任务。
    }
    public Task reconcile(long id,Scope scope,String cookie) {
        var detail=storage.detail(id,scope);
        if("COMPLETED".equals(detail.record().taskStatus()) || !detail.record().carrierBlocked())return detail.record();
        if("RUNNING".equals(detail.record().taskStatus()))return detail.record();
        Long started=storage.submittedAt(id);
        if(started==null)return detail.record();
        var carrier=new WaterInvasionModels.Scope(scope.projectId(),scope.gasReservoirId(),detail.record().carrierWellName());
        if(!collect(id,scope,detail,carrier,started,cookie))throw new ResponseStatusException(HttpStatus.CONFLICT,"尚未找到本次任务的完成通知及匹配输入的结果，保护未解除；请勿在旧平台同时计算此井");
        return storage.task(id,scope);
    }
    private boolean collect(long id,Scope scope,Detail detail,WaterInvasionModels.Scope carrier,long started,String cookie) {
        if(!WaterInvasionLegacyGateway.completed(legacy.logs(started,cookie),carrier,started))return false;
        var result=legacy.resultIfPresent(carrier,cookie);
        if(!WaterInvasionPayload.matches(detail.input(),result))return false;
        storage.save(id,carrier.wellName(),result);return true;
    }
    @PreDestroy public void close(){workers.shutdownNow();}
}

package com.grdp.studio.nodal;

import static com.grdp.studio.nodal.NodalModels.*;
import java.util.*;
import java.util.function.DoubleFunction;

/** Evaluates in bottom-pressure space, preserving failed segments instead of bridging them. */
public final class NodalSolver {
    public static final double PRESSURE_TOLERANCE = 1e-5;
    private static final int ITERATIONS = 50;
    private NodalSolver() {}

    public static Scenario solve(double pr, double end, int samples, DoubleFunction<Candidate> evaluate,
            boolean incomplete) {
        var points = new ArrayList<Candidate>();
        // q=0 is shown by the formation model separately. The flowing pressure calculator does not support it.
        for (int power = 6; power >= 1; power--) points.add(evaluate.apply(pr + (end - pr) / samples / Math.pow(10, power)));
        for (int i = 1; i <= samples; i++) points.add(evaluate.apply(pr + (end - pr) * i / samples));
        var roots = new ArrayList<Point>();
        var refined = new ArrayList<Candidate>();
        for (int i = 0; i < points.size(); i++) {
            Candidate current = points.get(i);
            if (i > 0) {
                Candidate previous = points.get(i - 1);
                if (previous.valid() && current.valid()) {
                    for (String code : List.of("liquidLoading", "erosion")) {
                        Double a = checkMargin(previous, code), b = checkMargin(current, code);
                        if (a != null && b != null && a * b < 0) {
                            Candidate lo = previous, hi = current;
                            for (int n = 0; n < ITERATIONS; n++) {
                                Candidate mid = evaluate.apply((lo.pressure() + hi.pressure()) / 2);
                                Double m = checkMargin(mid, code);
                                if (!mid.valid() || m == null) break;
                                if (Math.abs(m) < 1e-7) { refined.add(mid); break; }
                                if (a * m > 0) { lo = mid; a = m; } else hi = mid;
                                if (n == ITERATIONS - 1) refined.add(mid);
                            }
                        }
                    }
                    if (previous.margin() * current.margin() < 0) {
                        Candidate root = bisect(previous, current, evaluate, false);
                        if (root != null) { if (root.valid()) addRoot(roots, root); refined.add(root); }
                    }
                    if (previous.feasible() != current.feasible()) {
                        Candidate edge = bisect(previous, current, evaluate, true);
                        if (edge != null) refined.add(edge);
                    }
                }
            }
            if (current.valid() && Math.abs(current.margin()) <= PRESSURE_TOLERANCE) addRoot(roots, current);
            // Search local minima of |residual| as well as sign changes (tangent intersections).
            if (i > 0 && i + 1 < points.size()) {
                Candidate left = points.get(i - 1), right = points.get(i + 1);
                if (left.valid() && current.valid() && right.valid()
                        && Math.abs(current.margin()) < Math.abs(left.margin())
                        && Math.abs(current.margin()) < Math.abs(right.margin())) {
                    Candidate tangent = tangent(left.pressure(), right.pressure(), evaluate);
                    if (tangent != null) {
                        if (!tangent.valid()) refined.add(tangent);
                        else if (Math.abs(tangent.margin()) <= PRESSURE_TOLERANCE) { addRoot(roots, tangent); refined.add(tangent); }
                    }
                }
            }
            refined.add(current);
        }
        refined.sort(Comparator.comparingDouble(Candidate::rate));
        var intervals = new ArrayList<Interval>();
        Candidate start = null, last = null;
        for (Candidate point : refined) {
            if (point.feasible()) { if (start == null) start = point; last = point; }
            else if (start != null) { intervals.add(new Interval(asPoint(start), asPoint(last))); start = null; }
        }
        if (start != null) intervals.add(new Interval(asPoint(start), asPoint(last)));
        boolean truncated = points.getLast().feasible() && points.getLast().margin() > PRESSURE_TOLERANCE;
        boolean failures = refined.stream().anyMatch(p -> !p.valid() || p.checks().stream().anyMatch(c -> !Set.of("PASS", "FAIL").contains(c.status())));
        Point lastFeasible = intervals.isEmpty() ? null : intervals.getLast().to();
        // Unknown low-rate points cannot exceed an already verified higher-rate maximum.
        // Unknown points above it still prevent a global maximum claim.
        boolean unknownAbove = lastFeasible == null || refined.stream().anyMatch(p -> p.rate() >= lastFeasible.rate()
                && (!p.valid() || p.checks().stream().anyMatch(c -> !Set.of("PASS", "FAIL").contains(c.status()))));
        Point maximum = incomplete || unknownAbove || truncated ? null : lastFeasible;
        String status = incomplete ? "INCOMPLETE_CONSTRAINTS" : failures ? "PARTIAL_RESULT" : truncated ? "SEARCH_LIMIT_REACHED"
                : intervals.isEmpty() ? "NO_FEASIBLE_RANGE" : roots.size() > 1 ? "MULTIPLE_INTERSECTIONS" : "SUCCESS";
        var notes = new ArrayList<String>();
        notes.add("零气量端点仅显示地层压力；流动压力模型不评价零气量。区间为采样及边界细化结果。");
        if (roots.isEmpty()) notes.add("搜索范围内未找到井筒边界交点");
        if (truncated) notes.add("可行段到达搜索范围上限，请扩大井底压力范围");
        if (failures) notes.add(maximum == null ? "存在不可计算工况，尚不能确定全范围最大能力" : "低气量局部工况不可计算；不连接该缺口，已校核更高气量范围的最大能力");
        if (intervals.isEmpty()) notes.add("井筒边界与已选约束无共同可行气量区间");
        if (incomplete) notes.add("所选冲蚀约束尚未接入有效判据，最大能力未确定");
        if (roots.size() > 1) notes.add("存在多个边界交点，未进行动态稳定性判断");
        var formation = new ArrayList<Point>(); formation.add(new Point(0, pr, null));
        refined.forEach(p -> formation.add(asPoint(p)));
        String control = "未确定";
        if (maximum != null) {
            Candidate atMax = refined.stream().filter(p -> Math.abs(p.rate() - maximum.rate()) < 1e-9).findFirst().orElseThrow();
            var controls = new ArrayList<String>();
            if (Math.abs(atMax.margin()) <= 0.001) controls.add("井口压力/井筒输送边界");
            atMax.checks().stream().filter(c -> c.margin() != null && Math.abs(c.margin()) < 0.001).forEach(c -> controls.add(c.code()));
            control = controls.isEmpty() ? "约束可行段边界（数值细化）" : String.join("、", controls);
        }
        return new Scenario(pr, formation, roots, refined, intervals, maximum, control, status, notes);
    }
    private static Candidate tangent(double a, double b, DoubleFunction<Candidate> fn) {
        double lo = Math.min(a, b), hi = Math.max(a, b);
        for (int i = 0; i < ITERATIONS; i++) {
            double x = lo + (hi - lo) / 3, y = hi - (hi - lo) / 3;
            Candidate l = fn.apply(x), r = fn.apply(y);
            if (!l.valid() || !r.valid()) return !l.valid() ? l : r;
            if (Math.abs(l.margin()) < Math.abs(r.margin())) hi = y; else lo = x;
        }
        return fn.apply((lo + hi) / 2);
    }
    private static Double checkMargin(Candidate c, String code) {
        return c.checks().stream().filter(x -> x.code().equals(code)).map(Check::margin).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private static Candidate bisect(Candidate a, Candidate b, DoubleFunction<Candidate> fn, boolean feasible) {
        for (int i = 0; i < ITERATIONS; i++) {
            Candidate mid = fn.apply((a.pressure() + b.pressure()) / 2);
            if (!mid.valid()) return mid;
            if (Math.abs(a.pressure() - b.pressure()) < PRESSURE_TOLERANCE
                    && Math.abs(a.rate() - b.rate()) < 1e-6 * Math.max(1, mid.rate())) {
                if (feasible) return a.feasible() ? a : b;
                if (Math.abs(mid.margin()) <= PRESSURE_TOLERANCE) return mid;
            }
            if (feasible ? mid.feasible() == a.feasible() : mid.margin() * a.margin() > 0) a = mid; else b = mid;
        }
        return new Candidate(a.rate(), a.pressure(), null, List.of(), "节点边界细化未收敛");
    }
    private static Point asPoint(Candidate c) { return new Point(c.rate(), c.pressure(), c.reason()); }
    private static void addRoot(List<Point> points, Candidate c) {
        if (points.stream().noneMatch(p -> Math.abs(p.rate() - c.rate()) < 1e-5 * Math.max(1, c.rate()))) points.add(asPoint(c));
    }
}

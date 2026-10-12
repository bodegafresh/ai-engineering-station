package dev.aies.control.science;

/** P0.13: deterministic, inspectable unit-economics and trading expectation calculations. */
public final class ScientificProductMetrics {
    private ScientificProductMetrics() {}
    public record Business(double revenue, double variableCost, double fixedCost, int customers) {}
    public record BusinessResult(double contributionMargin, double netProfit, double profitPerCustomer, boolean profitable) {}
    public static BusinessResult business(Business b) {
        if(b == null || b.customers() < 1 || !finiteNonnegative(b.revenue()) || !finiteNonnegative(b.variableCost()) || !finiteNonnegative(b.fixedCost()))
            throw new IllegalArgumentException("Invalid business metrics");
        double contribution = b.revenue()-b.variableCost();
        double profit = contribution-b.fixedCost();
        return new BusinessResult(contribution,profit,profit/b.customers(),profit>0);
    }
    public record Trading(int wins,int losses,double averageWin,double averageLoss,double costPerTrade) {}
    public record TradingResult(int trades,double winRate,double expectancy,double breakEvenWinRate,
                                ScientificStatistics.WilsonInterval winRateInterval,boolean lowerBoundPositive) {}
    public static TradingResult trading(Trading t) {
        if(t==null || t.wins()<0 || t.losses()<0 || (long)t.wins()+t.losses()>Integer.MAX_VALUE || t.wins()+t.losses()<1
            || !finiteNonnegative(t.averageWin()) || !finiteNonnegative(t.averageLoss()) || !finiteNonnegative(t.costPerTrade())
            || t.averageWin()+t.averageLoss()==0) throw new IllegalArgumentException("Invalid trading metrics");
        int n=t.wins()+t.losses();
        double p=(double)t.wins()/n;
        double e=p*t.averageWin()-(1-p)*t.averageLoss()-t.costPerTrade();
        double threshold=(t.averageLoss()+t.costPerTrade())/(t.averageWin()+t.averageLoss());
        var ci=ScientificStatistics.wilson95(t.wins(),n);
        double conservative=ci.lower()*t.averageWin()-(1-ci.lower())*t.averageLoss()-t.costPerTrade();
        return new TradingResult(n,p,e,threshold,ci,conservative>0);
    }
    private static boolean finiteNonnegative(double x) {return Double.isFinite(x) && x>=0;}
}

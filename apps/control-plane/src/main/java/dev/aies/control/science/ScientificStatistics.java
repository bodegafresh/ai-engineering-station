package dev.aies.control.science;

/** Distribution-free descriptive summaries and Wilson interval for Bernoulli outcomes. */
public final class ScientificStatistics {
    private ScientificStatistics() {}
    public record WilsonInterval(double estimate, double lower, double upper, int successes, int trials) {}
    /** Two-sided 95% Wilson score interval, z=1.959963984540054. */
    public static WilsonInterval wilson95(int successes,int trials) {
        if(trials<1 || successes<0 || successes>trials) throw new IllegalArgumentException("Invalid counts");
        double z=1.959963984540054;
        double p=(double)successes/trials;
        double z2=z*z;
        double denominator=1+z2/trials;
        double center=(p+z2/(2*trials))/denominator;
        double radius=z*Math.sqrt((p*(1-p)+z2/(4*trials))/trials)/denominator;
        return new WilsonInterval(p,Math.max(0,center-radius),Math.min(1,center+radius),successes,trials);
    }
    /** Little's law in a stable system, consistent units: L=lambda*W. */
    public static double expectedWorkInProgress(double arrivalsPerHour,double hoursInSystem) {
        if(!Double.isFinite(arrivalsPerHour)||!Double.isFinite(hoursInSystem)||arrivalsPerHour<0||hoursInSystem<0)
            throw new IllegalArgumentException("Invalid flow metrics");
        return arrivalsPerHour*hoursInSystem;
    }
}

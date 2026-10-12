package dev.aies.control.science;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScientificProductBoundariesTest {
 @Test void businessUnitEconomicsAndInvalidInputs() {
  var r=ScientificProductMetrics.business(new ScientificProductMetrics.Business(1000,400,300,10));
  assertEquals(600,r.contributionMargin(),1e-12);
  assertEquals(300,r.netProfit(),1e-12);
  assertEquals(30,r.profitPerCustomer(),1e-12);
  assertTrue(r.profitable());
  assertFalse(ScientificProductMetrics.business(new ScientificProductMetrics.Business(100,90,20,1)).profitable());
  assertThrows(IllegalArgumentException.class,()->ScientificProductMetrics.business(new ScientificProductMetrics.Business(Double.NaN,1,1,1)));
  assertThrows(IllegalArgumentException.class,()->ScientificProductMetrics.business(new ScientificProductMetrics.Business(100,1,1,0)));
 }
 @Test void tradingExpectedValueAndConservativeBound() {
  var r=ScientificProductMetrics.trading(new ScientificProductMetrics.Trading(60,40,0.85,1,0));
  assertEquals(100,r.trades());
  assertEquals(0.6,r.winRate(),1e-12);
  assertEquals(0.11,r.expectancy(),1e-12);
  assertEquals(1/1.85,r.breakEvenWinRate(),1e-12);
  assertTrue(r.winRateInterval().lower()<=r.winRate());
  assertTrue(r.winRateInterval().upper()>=r.winRate());
  assertThrows(IllegalArgumentException.class,()->ScientificProductMetrics.trading(new ScientificProductMetrics.Trading(0,0,1,1,0)));
  assertThrows(IllegalArgumentException.class,()->ScientificProductMetrics.trading(new ScientificProductMetrics.Trading(2,1,Double.POSITIVE_INFINITY,1,0)));
 }
}

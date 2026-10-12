package dev.aies.control.science;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScientificProductMetricsTest {
    @Test void businessUnitEconomics(){
        var result=ScientificProductMetrics.business(new ScientificProductMetrics.Business(1000,300,400,10));
        assertEquals(300,result.netProfit(),1e-9);
        assertEquals(30,result.profitPerCustomer(),1e-9);
        assertTrue(result.profitable());
    }
    @Test void tradingExpectancyAndConservativeBound(){
        var result=ScientificProductMetrics.trading(new ScientificProductMetrics.Trading(60,40,0.85,1,0));
        assertEquals(.11,result.expectancy(),1e-9);
        assertEquals(1/1.85,result.breakEvenWinRate(),1e-9);
        assertFalse(result.lowerBoundPositive());
    }
    @Test void rejectsInvalid(){
        assertThrows(IllegalArgumentException.class,()->ScientificProductMetrics.trading(new ScientificProductMetrics.Trading(0,0,1,1,0)));
        assertThrows(IllegalArgumentException.class,()->ScientificProductMetrics.business(new ScientificProductMetrics.Business(Double.NaN,0,0,1)));
    }
}

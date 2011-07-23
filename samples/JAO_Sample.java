import junit.framework.Assert;
import junit.framework.TestCase;



public class JAO_Sample extends TestCase 
{
	
	public void testExactDoubles(double d1, double d2)
	{
		Assert.assertEquals(d1, d2);
	}
	
	public void testTrue(boolean b)
	{
		Assert.assertEquals(true, b);
	}
	
	public void testFalse(boolean b)
	{
		Assert.assertEquals("Wow this is bad", false, b);
	}
	
	public void testWrongOrder(int i)
	{
		Assert.assertEquals(i, 10);
	}
	
	public void testAutoBoxNotNull(int i)
	{
		Assert.assertNotNull(i);
		Assert.assertNotNull(i == 3);
	}
	
	public void test3ArgNP(float foo)
	{
	    Assert.assertEquals(1.0f, foo, 0.1);
	}
}

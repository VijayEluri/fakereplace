package org.fakereplace.test.replacement.staticmethod;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.fakereplace.test.util.ClassReplacer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

public class StaticMethodTest
{
   @BeforeClass(groups = "staticmethod")
   public void setup()
   {
      ClassReplacer rep = new ClassReplacer();
      rep.queueClassForReplacement(StaticClass.class, StaticClass1.class);
      rep.replaceQueuedClasses();
   }

   @Test(groups = "staticmethod")
   public void testStaticMethodByReflection() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {

      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("method1");

      m = c.getMethod("method2");

      Integer res = (Integer) m.invoke(null);
      assert res == 1 : "Failed to replace static method";
   }

   @Test(groups = "staticmethod")
   public void testStaticFieldAccessByReflection() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {

      Class c = StaticClass.class;
      Method m = c.getMethod("add");

      m.invoke(null);

      m = c.getMethod("getValue");
      Integer res = (Integer) m.invoke(null);

      assert res == 1 : "Failed to replace static method";
   }

   @Test(groups = "staticmethod")
   public void testIntPrimitiveReturnType() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {

      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("getInt");
      Integer res = (Integer) m.invoke(null);
      assert res == 10 : "Failed to replace static method with primitive return value";
   }

   @Test(groups = "staticmethod")
   public void testLongPrimitiveReturnType() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {

      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("getLong");
      Long res = (Long) m.invoke(null);
      assert res == 11 : "Failed to replace static method with primitive return value";
   }

   @Test(groups = "staticmethod")
   public void testIntegerMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {

      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("integerAdd", Integer.class);
      Integer res = (Integer) m.invoke(null, new Integer(10));
      assert res == 11 : "Failed to replace static method with Object method parameter";
   }

   @Test(groups = "staticmethod")
   public void testIntMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("intAdd", int.class);
      Integer res = (Integer) m.invoke(null, 10);
      assert res == 11;
   }

   @Test(groups = "staticmethod")
   public void testShortMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("shortAdd", short.class);
      Short res = (Short) m.invoke(null, (short) 10);
      assert res == 11;
   }

   @Test(groups = "staticmethod")
   public void testLongMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("longAdd", long.class);
      Long res = (Long) m.invoke(null, (long) 10);
      assert res == 11;
   }

   @Test(groups = "staticmethod")
   public void testByteMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("byteAdd", byte.class);
      Byte res = (Byte) m.invoke(null, (byte) 10);
      assert res == 11;
   }

   @Test(groups = "staticmethod")
   public void testFloatMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("floatAdd", float.class);
      Float res = (Float) m.invoke(null, 0.0f);
      assert res == 1;
   }

   @Test(groups = "staticmethod")
   public void testDoubleMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("doubleAdd", double.class);
      Double res = (Double) m.invoke(null, 0.0f);
      assert res == 1;
   }

   @Test(groups = "staticmethod")
   public void testCharMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("charAdd", char.class);
      Character res = (Character) m.invoke(null, 'a');
      assert res == 'b';
   }

   @Test(groups = "staticmethod")
   public void testBooleanMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      Method m = c.getMethod("negate", boolean.class);
      Boolean res = (Boolean) m.invoke(null, false);
      assert res.booleanValue();
   }

   @Test(groups = "staticmethod")
   public void testArrayMethodParameter() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      StaticClass ns = new StaticClass();
      Class c = StaticClass.class;
      int[] aray = new int[1];
      aray[0] = 34;
      Method m = c.getMethod("arrayMethod", int[].class);
      int[] res = (int[]) m.invoke(null, aray);
      assert res[0] == 35;
   }
}

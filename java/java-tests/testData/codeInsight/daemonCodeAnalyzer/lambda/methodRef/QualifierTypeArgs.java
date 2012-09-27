class MyTest {
  interface I {
    String m(Foo<String> f);
  }

  class Foo<X> {
    String foo() {
      return null;
    }

    {
      I i = Foo<String> :: foo;
      <error descr="Incompatible types. Found: '<method reference>', required: 'MyTest.I'">I i1 = Foo<Integer> :: foo;</error>
    }
  }
}

class MyTest1 {
    interface I {
       String m(Foo f);
    }

    static class Foo<X> {
       String foo() { return null; }

       static void test() {
          I i = Foo::foo;
       }
    }
}

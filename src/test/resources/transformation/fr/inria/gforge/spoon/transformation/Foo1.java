package fr.inria.gforge.spoon.transformation;

// must **not** be in src/test/java so that it is not in the class path
public class Foo1 implements IFoo {

    public int n() {
        return 0;
    }

    public int m() {
        return 3 + 2;
    }

    public int b() {
        return 3 * 2;
    }
}

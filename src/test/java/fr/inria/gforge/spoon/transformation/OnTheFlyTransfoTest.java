package fr.inria.gforge.spoon.transformation;

import org.junit.Before;
import org.junit.Test;
import org.mdkt.compiler.InMemoryJavaCompiler;
import spoon.Launcher;
import spoon.compiler.SpoonResource;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.cu.position.NoSourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.Filter;
import spoon.reflect.visitor.filter.NamedElementFilter;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;
import spoon.support.reflect.code.CtBlockImpl;
import spoon.support.reflect.declaration.CtMethodImpl;
import spoon.support.reflect.reference.CtTypeReferenceImpl;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

// shows how to test a transformation
// with assertions on the transformed AST
// and assertion on the resulted behavior
public class OnTheFlyTransfoTest {
    Launcher l;

    @Before
    public void setUp() throws Exception {
        l = new Launcher();
    }

    @Test
    public void example() throws Exception {

        // required for having IFoo.class in the classpath in Maven
        l.setArgs(new String[]{"--source-classpath", "target/test-classes"});

        l.addInputResource("src/test/resources/transformation/");
        l.setSourceOutputDirectory("/tmp/transform");
        l.buildModel();

        CtClass foo = l.getFactory().Package().getRootPackage().getElements(new NamedElementFilter<>(CtClass.class, "Foo1")).get(0);
        testInitialClass(foo);

        transformPlusToMinus(foo);

        deleteMethod(foo, "b");


        // there are no more additions in the code
        assertEquals(0, foo.getElements(new Filter<CtBinaryOperator<?>>() {
            @Override
            public boolean matches(CtBinaryOperator<?> arg0) {
                return arg0.getKind() == BinaryOperatorKind.PLUS;
            }
        }).size());

        // TODO can we read the code from a file?
        addCodeTo(foo, "\tprivate int counter = 0;" +
                "\tfinal public String foo() {\n" +
                "\t\tSystem.out.println(\"This is a test\");\n" +
                "\t\tSystem.out.println(\"Line 2\");\n" +
                "\t\treturn \"foo has been called\";" +
                "\t}" +
                "\n" +
                "\tpublic int add(int value1, int value2) {\n" +
                "\t\ttry {\n" +
                "\t\t\treturn value1 + value2;\n" +
                "\t\t} catch(RuntimeException ignored) {\n" +
                "\t\t\tSystem.out.println(\"ignoring \"+ignored.getMessage());\n" +
                "\t\t\tthrow ignored;\n" +
                "\t\t} finally {\n" +
                "\t\t\tcounter++;\n" +
                "\t\t}\n" +
                "}");

        replaceMethodBody(foo, "foo", "\t\tSystem.out.println(\"this is replacement code\");\n" +
                "\t\treturn \"foo has been called\";");

        testCompile(foo);

        // Write out the classes to the output directory
        l.prettyprint();
    }

    final private static String methodTemplate = "public void %s() {\n" +
            "%s\n" +
            "}";
    // TODO name is not enough might need parameters too
    private void replaceMethodBody(CtClass clazz, String methodName, String body) throws NoSuchMethodException {
        Set<CtMethod> methods = clazz.getMethods();
        CtMethod existingMethod = methods.stream()
                .filter(method -> method.getSimpleName().equals(methodName))
                .findFirst().orElse(null);
        if (existingMethod == null) {
            throw new NoSuchMethodException("Did not find a method "+methodName);
        }

        String existingBody = existingMethod.toString();
        int firstBrace = existingBody.indexOf("{");
        int lastBrace = existingBody.lastIndexOf("}");
        String replacementBody = existingBody.substring(0, firstBrace + 1)
                + body + existingBody.substring(lastBrace);
        Collection<CtElement> changes = getAdditions(clazz, replacementBody);

        changes.forEach(change -> {
            if (change instanceof CtMethod) {
                existingMethod.setBody(((CtMethod) change).getBody());
            } else {
                System.err.println("Unknown change of type " + change.getClass().getSimpleName());
            }
        });
    }

    private void addCodeTo(CtClass clazz, String code) {
        Collection<CtElement> changes = getAdditions(clazz, code);

        changes.forEach(change -> {
            if (change instanceof CtField) {
                clazz.addField((CtField) change);
            } else if (change instanceof CtMethod) {
                clazz.addMethod((CtMethod) change);
            } else {
                System.err.println("Unknown change of type " + change.getClass().getSimpleName());
            }
        });
    }

    private void testCompile(CtClass foo) throws Exception {
        // compiling and testing the transformed class
        Class<?> fooClass;
        fooClass = InMemoryJavaCompiler.newInstance().compile(foo.getQualifiedName(), "package " + foo.getPackage().getQualifiedName() + ";" + foo.toString());
        IFoo y = (IFoo) fooClass.newInstance();
        // testing its behavior with subtraction
        assertEquals(1, y.m());
        Method method = y.getClass().getMethod("foo");
        Object output = method.invoke(y);
        assertEquals(String.valueOf(output), "foo has been called");
    }

    private Collection<CtElement> getAdditions(CtClass clazz, String code) {
        Launcher l = new Launcher();

        String clazzBody = clazz.toString();
        int lastBrace = clazzBody.lastIndexOf("}");

        String className = clazz.getSimpleName();
        String classCode = String.format("package %s;\n" +
                "\n" +
                "public class %s {\n" +
                "%s\n" +
                "%s", clazz.getPackage(), className,
                code, clazzBody.substring(lastBrace));
        SpoonResource resource = new VirtualFile(classCode, className +".java");
        l.addInputResource(resource);
        l.buildModel();
        CtClass temp = l.getFactory().Package().getRootPackage().getElements(new NamedElementFilter<>(CtClass.class, className)).get(0);

        List<CtElement> elements = temp.getElements(new Filter<CtElement>() {
            @Override
            public boolean matches(CtElement element) {
                if (element instanceof CtMethod == false
                        && element instanceof CtField == false) {
                    return false;
                }
                SourcePosition position = element.getPosition();
                return position instanceof NoSourcePosition == false;
            }
        });
        return elements.stream()
                .map(element -> {
                    if (element instanceof CtMethod) {
                        return copyMethod((CtMethod) element);
                    }
                    return element;
                })
                .collect(Collectors.toList());
    }

    private CtMethod copyMethod(CtMethod source) {
        CtMethod copy = new CtMethodImpl();
        copy.setSimpleName(source.getSimpleName());
        copy.setModifiers(source.getModifiers());
        copy.setType(source.getType());
        copy.setParameters(source.getParameters());
        copy.setThrownTypes(source.getThrownTypes());
        copy.setBody(copyBody(source.getBody()));
        copy.setAnnotations(source.getAnnotations());
        copy.setComments(source.getComments());
        return copy;
    }

    private CtBlock copyBody(CtBlock body) {
        CtBlock copy = new CtBlockImpl();
        copy.setAnnotations(body.getAnnotations());
        copy.setComments(body.getComments());
        copy.setParent(null);
        copy.setPosition(null);
        body.getStatements().stream()
                .forEach(statement -> copy.addStatement(copyStatement(statement)));
        return copy;
    }

    private CtStatement copyStatement(CtStatement statement) {
        CtStatement copy = statement.clone();
        if (copy instanceof CtTry) {
            CtTry source = (CtTry) statement;
            CtTry asTry = (CtTry) copy;
            CtBlock finalizerBlock = copyBody(source.getFinalizer());
            asTry.setFinalizer(finalizerBlock);
            asTry.setCatchers(
                    source.getCatchers().stream()
                            .map(catcher -> {
                                CtCatch catchCopy = catcher.clone();
                                catchCopy.setParent(null);
                                catchCopy.setPosition(null);
                                CtBlock catchBody = copyBody(catcher.getBody());
                                catchCopy.setBody(catchBody);
                                return catchCopy;
                            })
                            .collect(Collectors.toList()));
        }
        if (copy instanceof CtUnaryOperator) {
            CtUnaryOperator asUnaryOperator = (CtUnaryOperator) copy;
            asUnaryOperator.setType(copyType(asUnaryOperator.getType()));
            asUnaryOperator.setPosition(null);
            asUnaryOperator.setParent(null);
            asUnaryOperator.setOperand(copyOperand(asUnaryOperator.getOperand()));
        }
        copy.setParent(null);
        copy.setPosition(null);
        return copy;
    }

    private CtTypeReference copyType(CtTypeReference type) {
        CtTypeReference copy = type.clone();
        copy.setDeclaringType(null);
        copy.setPackage(null);
        copy.setParent(null);
        copy.setPosition(null);
        return copy;
    }

    private CtExpression copyOperand(CtExpression operand) {
        CtExpression copy = operand.clone();
        copy.setParent(null);
        copy.setPosition(null);
        return copy;
    }

    private CtMethod createMethod(Launcher l, CtClass foo, List<String> modifiers, Class<?> returnType, String methodName, String body) {
        CtMethod newMethod = new CtMethodImpl();
        newMethod.setSimpleName(methodName);

        Set<ModifierKind> methodModifiers = new HashSet<>();
        modifiers.stream().forEach(modifier -> {
            ModifierKind asKind = ModifierKind.valueOf(modifier.toUpperCase());
            methodModifiers.add(asKind);
        });
        newMethod.setModifiers(methodModifiers);

        CtTypeReference methodReturnType = new CtTypeReferenceImpl();
        methodReturnType.setSimpleName(returnType.getSimpleName());
        newMethod.setType(methodReturnType);

        CtCodeSnippetStatement snippet = l.getFactory().Core().createCodeSnippetStatement();
        snippet.setValue(body);
        newMethod.setBody(snippet);
        return newMethod;
    }

    private void deleteMethod(CtClass foo, String methodName) {
        for (Object e : foo.getElements(new TypeFilter(CtMethod.class))) {
            CtMethod method = (CtMethod) e;
            if (method.getSimpleName().equals(methodName)) {
                method.delete();
            }
        }
    }

    private void transformPlusToMinus(CtClass foo) {
        // now we apply a transformation
        // we replace "+" by "-"
        for (Object e : foo.getElements(new TypeFilter(CtBinaryOperator.class))) {
            CtBinaryOperator op = (CtBinaryOperator) e;
            if (op.getKind() == BinaryOperatorKind.PLUS) {
                op.setKind(BinaryOperatorKind.MINUS);
            }
        }
    }

    private void testInitialClass(CtClass foo) throws Exception {
        // compiling and testing the initial class
        Class<?> fooClass = InMemoryJavaCompiler.newInstance().compile(foo.getQualifiedName(), "package " + foo.getPackage().getQualifiedName() + ";" + foo.toString());
        IFoo x = (IFoo) fooClass.newInstance();
        // testing its behavior
        assertEquals(5, x.m());
    }
}

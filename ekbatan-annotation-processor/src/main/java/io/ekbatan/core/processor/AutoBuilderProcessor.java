package io.ekbatan.core.processor;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Generated;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

/**
 * Annotation processor for {@code @AutoBuilder}. For each annotated class that extends
 * {@code Model} or {@code Entity}, generates a {@code *Builder} class that extends the
 * framework's base builder with field setters matching the annotated class's fields, plus
 * a static factory method matching the class name in camelCase.
 *
 * <p>The processor reads the annotated class's fields, generates a sibling builder class
 * via JavaPoet, and writes it back into the user's compile output. Without this AP, users
 * would have to hand-write builders that thread the base framework's required fields
 * ({@code id}, {@code state}, {@code version}, {@code createdDate}/{@code updatedDate} for
 * Models) through their own builder hierarchies.
 *
 * <p>Wire-up: add {@code annotationProcessor("io.github.ekbatan-io:ekbatan-annotation-processor:<v>")}
 * to your module's Gradle dependencies. The Spring Boot / Quarkus / Micronaut samples all
 * include this on their starter pages.
 */
@SupportedAnnotationTypes("io.ekbatan.core.processor.AutoBuilder")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class AutoBuilderProcessor extends AbstractProcessor {

    /** Required by the Java {@code javax.annotation.processing} SPI; instantiated reflectively by {@code javac}. */
    public AutoBuilderProcessor() {}

    private static final String MODEL_PACKAGE = "io.ekbatan.core.domain";
    private static final String MODEL_CLASS = "Model";
    private static final String ENTITY_CLASS = "Entity";
    private static final String BUILDER_CLASS = "Builder";

    private Elements elementUtils;
    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(AutoBuilder.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                error(element, "Only classes can be annotated with @AutoBuilder");
                return true;
            }

            TypeElement classElement = (TypeElement) element;
            try {
                generateBuilderClass(classElement);
            } catch (IOException e) {
                error(element, "Error generating builder for %s: %s", classElement.getQualifiedName(), e.getMessage());
            } catch (RuntimeException e) {
                // A processor runs inside javac, so an escaping unchecked exception is reported as
                // "An annotation processor threw an uncaught exception" followed by a stack trace
                // through Ekbatan's internals - with nothing naming the class that triggered it.
                // Reporting against the element keeps it a normal compile error the user can act
                // on. The specific known trigger is validated up front in generateBuilderClass;
                // this is the backstop for everything not yet anticipated.
                error(
                        element,
                        "Failed to generate builder for %s: %s: %s",
                        classElement.getQualifiedName(),
                        e.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        return true;
    }

    private void generateBuilderClass(TypeElement classElement) throws IOException {
        final var packageName =
                elementUtils.getPackageOf(classElement).getQualifiedName().toString();
        final var className = classElement.getSimpleName().toString();
        final var builderClassName = className + "Builder";

        // The generated builder reads ID and STATE straight off the superclass's type arguments,
        // so @AutoBuilder only means anything on a direct subclass of Model or Entity. Validated
        // here rather than assumed: the casts and index reads below used to run unguarded, so
        // annotating anything else - a plain class, an indirect subclass, a raw extends - threw
        // ClassCastException or IndexOutOfBoundsException out of the processor. javac surfaces
        // that as an internal crash with a stack trace through Ekbatan, naming neither the
        // annotated class nor the mistake.
        final var superclass = classElement.getSuperclass();
        if (!(superclass instanceof DeclaredType declaredSuperclass)) {
            error(
                    classElement,
                    "@AutoBuilder requires %s to directly extend %s.%s or %s.%s",
                    classElement.getQualifiedName(),
                    MODEL_PACKAGE,
                    MODEL_CLASS,
                    MODEL_PACKAGE,
                    ENTITY_CLASS);
            return;
        }
        final var superClassElement = (TypeElement) declaredSuperclass.asElement();
        final var superClassName = superClassElement.getSimpleName().toString();
        final var superQualifiedName = superClassElement.getQualifiedName().toString();
        if (!superQualifiedName.equals(MODEL_PACKAGE + "." + MODEL_CLASS)
                && !superQualifiedName.equals(MODEL_PACKAGE + "." + ENTITY_CLASS)) {
            error(
                    classElement,
                    "@AutoBuilder requires %s to directly extend %s.%s or %s.%s, but it extends %s",
                    classElement.getQualifiedName(),
                    MODEL_PACKAGE,
                    MODEL_CLASS,
                    MODEL_PACKAGE,
                    ENTITY_CLASS,
                    superQualifiedName);
            return;
        }

        final var typeArguments = declaredSuperclass.getTypeArguments();
        if (typeArguments.size() < 3) {
            error(
                    classElement,
                    "@AutoBuilder requires %s to extend %s with all of its type arguments spelled "
                            + "out (raw %s is not supported)",
                    classElement.getQualifiedName(),
                    superClassName,
                    superClassName);
            return;
        }

        final var modelType = TypeName.get(classElement.asType());

        // Extract type parameters based on superclass type
        final var idType = TypeName.get(typeArguments.get(1)); // ID type is always second parameter
        final var stateType = TypeName.get(typeArguments.get(2)); // STATE type is always third parameter

        // A generic domain class has to hand its type variables to the builder. Without this the
        // builder was declared bare while its fields, setters and superclass argument still
        // referred to T, so the generated file failed to compile with "cannot find symbol: class
        // T" - reported against a file the user never wrote and cannot open. Empty for every
        // non-generic class, where addTypeVariables is a no-op and builderTypeName collapses to
        // the plain ClassName, so the generated output is unchanged.
        final var typeVariables = classElement.getTypeParameters().stream()
                .map(TypeVariableName::get)
                .toList();
        final var rawBuilderType = ClassName.get(packageName, builderClassName);
        final TypeName builderTypeName = typeVariables.isEmpty()
                ? rawBuilderType
                : ParameterizedTypeName.get(rawBuilderType, typeVariables.toArray(TypeName[]::new));

        // Create the builder class with proper type parameters and @Generated annotation
        final var typeSpecBuilder = TypeSpec.classBuilder(builderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addTypeVariables(typeVariables)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", AutoBuilderProcessor.class.getName())
                        .build())
                .superclass(ParameterizedTypeName.get(
                        ClassName.get(MODEL_PACKAGE, superClassName, BUILDER_CLASS),
                        idType, // ID<M>
                        builderTypeName, // B (builder type)
                        modelType, // M (model type)
                        stateType // STATE (dynamic)
                        ));

        // Find all non-static fields in the target class
        List<VariableElement> fields = new ArrayList<>();
        for (Element enclosed : classElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && !enclosed.getModifiers().contains(Modifier.STATIC)) {
                fields.add((VariableElement) enclosed);
            }
        }

        // Add fields to the builder (without private modifier for package access)
        for (VariableElement field : fields) {
            String fieldName = field.getSimpleName().toString();
            TypeName fieldType = TypeName.get(field.asType());

            // Add the field (package-private)
            typeSpecBuilder.addField(fieldType, fieldName);

            MethodSpec setter = MethodSpec.methodBuilder(fieldName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(builderTypeName)
                    .addParameter(fieldType, fieldName)
                    .addStatement("this.$N = $N", fieldName, fieldName)
                    .addStatement("return this")
                    .build();
            typeSpecBuilder.addMethod(setter);

            // Add the getter method
            final var getter = MethodSpec.methodBuilder(fieldName) // Same name as field
                    .addModifiers(Modifier.PUBLIC)
                    .returns(fieldType)
                    .addStatement("return this.$N", fieldName)
                    .build();
            typeSpecBuilder.addMethod(getter);
        }

        // Add private constructor
        final var constructor =
                MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build();
        typeSpecBuilder.addMethod(constructor);

        // Add static factory method (using classname in lowercase as method name)
        final var factoryMethodName =
                Character.toLowerCase(className.charAt(0)) + (className.length() > 1 ? className.substring(1) : "");
        // A static method cannot see the class's type variables, so it declares its own copy:
        // "public static <T> BoxBuilder<T> box()". Again a no-op when there are none.
        final var factoryMethod = MethodSpec.methodBuilder(factoryMethodName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariables(typeVariables)
                .returns(builderTypeName)
                .addStatement("return new $T()", builderTypeName)
                .build();
        typeSpecBuilder.addMethod(factoryMethod);

        // Add build method
        final var buildMethod = MethodSpec.methodBuilder("build")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(modelType)
                .addStatement("return new $T(this)", modelType)
                .build();
        typeSpecBuilder.addMethod(buildMethod);

        // Write the generated class to a file
        JavaFile.builder(packageName, typeSpecBuilder.build()).build().writeTo(filer);
    }

    private void error(Element e, String msg, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), e);
    }
}

package ru.fridorin.translator;

import org.junit.Test;
import static org.junit.Assert.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.util.List;

/** Inspects class files without initializing Minecraft or applying mixins. */
public class MixinTargetTest {
    private ClassNode read(String name) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull("Missing class: " + name, stream);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allMixinTargetsExistAndScoreboardInvocationMatches() throws Exception {
        int checked = 0;
        for (String name : List.of("Gui", "Screen", "Entity", "ItemStack", "PlayerInfo", "ChatComponent")) {
            ClassNode mixin = read("ru/fridorin/translator/mixin/" + name + "Mixin");
            AnnotationNode mixinAnnotation = mixin.invisibleAnnotations.stream()
                    .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            Type targetType = ((List<Type>) value(mixinAnnotation, "value")).getFirst();
            ClassNode target = read(targetType.getInternalName());
            for (MethodNode method : mixin.methods) {
                if (method.visibleAnnotations == null) continue;
                for (AnnotationNode injection : method.visibleAnnotations) {
                    if (!injection.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                            && !injection.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;")) continue;
                    checked++;
                    String targetName = ((List<String>) value(injection, "method")).getFirst();
                    List<MethodNode> targets = target.methods.stream().filter(m -> m.name.equals(targetName)).toList();
                    assertFalse(name + ": missing " + targetName, targets.isEmpty());
                    if (name.equals("Gui") || name.equals("Screen")) {
                        AnnotationNode at = ((List<AnnotationNode>) value(injection, "at")).getFirst();
                        String invocation = (String) value(at, "target");
                        long matches = targets.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                                .filter(i -> i instanceof MethodInsnNode)
                                .map(i -> (MethodInsnNode) i)
                                .filter(i -> invocation.equals("L" + i.owner + ";" + i.name + i.desc)).count();
                        assertEquals("Drawing hook must match the game's text calls: " + name, name.equals("Gui") ? 3 : 2, matches);
                    }
                }
            }
        }
        assertEquals("Every injection must be checked", 5, checked);
    }
}

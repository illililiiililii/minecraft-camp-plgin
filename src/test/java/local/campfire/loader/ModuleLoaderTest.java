package local.campfire.loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;
class ModuleLoaderTest {
    @TempDir Path temp;
    @Test void separateLoadsNeverReuseThePreviousRuntimeClass()throws Exception{
        Path jar=temp.resolve("runtime.jar"),classes=Path.of("target/classes");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(classes)){
            for(Path p:paths.filter(Files::isRegularFile).toList()){
                String name=classes.relativize(p).toString().replace('\\','/');
                if(name.startsWith("local/campfire/loader/")||name.equals("plugin.yml"))continue;
                out.putNextEntry(new JarEntry(name));Files.copy(p,out);out.closeEntry();
            }
        }
        try(var first=new CampfireLoader.ModuleLoader(jar,CampfireLoader.class.getClassLoader());var second=new CampfireLoader.ModuleLoader(jar,CampfireLoader.class.getClassLoader())){
            Class<?> a=first.loadClass("local.campfire.CampfireRuntime"),b=second.loadClass("local.campfire.CampfireRuntime");
            assertNotSame(a,b);assertSame(first,a.getClassLoader());assertSame(second,b.getClassLoader());
            assertTrue(org.bukkit.command.TabExecutor.class.isAssignableFrom(a));assertEquals(Object.class,a.getSuperclass());
        }
    }
    @Test void checksumDetectsAChangedModule()throws Exception{
        Path p=temp.resolve("module.jar");Files.writeString(p,"abc");String before=CampfireLoader.sha(p);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",before);
        Files.writeString(p,"abcd");assertNotEquals(before,CampfireLoader.sha(p));
    }
}

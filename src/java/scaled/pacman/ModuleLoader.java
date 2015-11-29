//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads classes for a particular module. A module has two kinds of depends: binary depends, which
 * are private to the package, and will be searched first, and module depends, wherein a Scaled
 * package module depends on another module (possibly in a different package).
 */
public class ModuleLoader extends URLClassLoader {

  public final Module mod;
  public final ClassLoader[] delegates;

  public static URL toURL (Path path) {
    try { return path.toUri().toURL(); }
    catch (MalformedURLException e) { throw new AssertionError(e); }
  }

  public ModuleLoader (Depends.Resolver resolve, Depends depends) {
    super(toURLs(depends.mod.classpath(), depends.binaryDeps.keySet()),
          // we need to explicitly pass our classloader as the parent, as the system classloader
          // contains just the pacman bootstrap code, but we need pacman (and mfetcher) to be in the
          // classloader chain because the alternative is classloader madness
          ModuleLoader.class.getClassLoader());
    this.mod = depends.mod;
    this.delegates = new ClassLoader[depends.systemDeps.size()+depends.moduleDeps.size()];
    int ii = 0;
    for (Path path : depends.systemDeps.keySet()) delegates[ii++] = resolve.systemLoader(path);
    for (Depends dep : depends.moduleDeps) delegates[ii++] = dep.mod.loader(resolve);
  }

  public Source source () {
    return mod.source;
  }

  public void dump (String depth) {
    System.out.println(depth + this);
    depth += " ";
    for (URL url : getURLs()) {
      System.out.println(depth + url);
    }
    for (ClassLoader delegate : delegates) {
      if (delegate instanceof ModuleLoader) {
        ((ModuleLoader)delegate).dump(depth);
      } else if (delegate instanceof URLClassLoader) {
        URLClassLoader ul = (URLClassLoader)delegate;
        System.out.println(depth + "URLClassLoader(" + Arrays.asList(ul.getURLs()) + ")");
      } else {
        System.out.println(depth + delegate);
      }
    }
  }

  @Override public URL getResource (String path) {
    URL rsrc = super.getResource(path);
    if (rsrc != null) return rsrc;
    for (ClassLoader loader : delegates) {
      URL drsrc = loader.getResource(path);
      if (drsrc != null) return drsrc;
    }
    return null;
  }

  @Override protected Class<?> findClass (String name) throws ClassNotFoundException {
    // System.err.println("Seeking "+ name +" in "+ source);
    try { return super.findClass(name); }
    catch (ClassNotFoundException cnfe) {} // check our module deps
    for (ClassLoader loader : delegates) {
      try { return loader.loadClass(name); }
      catch (ClassNotFoundException cnfe) {} // keep going
    }
    throw new ClassNotFoundException(source() + " missing dependency: " + name);
  }

  @Override public String toString () {
    return "ModLoader(" + source() + ")";
  }

  private static URL[] toURLs (Path classes, Collection<Path> paths) {
    URL[] urls = new URL[1+paths.size()];
    int ii = 0;
    urls[ii++] = toURL(classes);
    for (Path path : paths) urls[ii++] = toURL(path);
    return urls;
  }
}

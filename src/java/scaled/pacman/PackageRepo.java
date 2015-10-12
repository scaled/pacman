//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class PackageRepo {

  /** Used to observe package goings on. */
  public static interface Observer {
    void packageAdded (Package pkg);
    void packageRemoved (Package pkg);
  }

  /** A hook for Scaled to observe package goings on. */
  public Observer observer;

  /** The top-level Scaled metadata directory. */
  public final Path metaDir = locateMetaDir();

  /** Used to resolve Maven artifacts. */
  public final MavenResolver mvn = new MavenResolver();

  /** Used to resolve System artifacts. */
  public final SystemResolver sys = new SystemResolver();

  /** Used to resolve dependencies. */
  public final Depends.Resolver resolver = new Depends.Resolver() {
    public Optional<Module> moduleBySource (Source source) {
      Package pkg = _pkgs.get(source.packageSource());
      return Optional.ofNullable(pkg == null ? null : pkg.module(source.module()));
    }
    public Map<RepoId,Path> resolve (List<RepoId> ids) {
      return mvn.resolve(ids);
    }
    public Path resolve (SystemId id) {
      return sys.resolve(id);
    }
    public boolean isSystem (RepoId id) {
      Set<String> arts = SYSTEM_DEPS.get(id.groupId);
      return (arts != null) && arts.contains(id.artifactId);
    }
    public ClassLoader systemLoader (Path path) {
      ClassLoader cl = _systemLoaders.get(path);
      if (cl == null) _systemLoaders.put(
        path, cl = new URLClassLoader(new URL[] { ModuleLoader.toURL(path) }));
      return cl;
    }

    private Map<Path,ClassLoader> _systemLoaders = new HashMap<>();
  };

  /** Creates (if necessary) and returns a directory in the top-level Scaled metadata directory. */
  public Path metaDir (String name) throws IOException {
    Path dir = metaDir.resolve(name);
    Files.createDirectories(dir);
    return dir;
  }

  /** Returns the directory in which all packages are installed. */
  public Path packagesDir () throws IOException {
    return metaDir("Packages");
  }

  /** Returns the directory in which a package named {@code name} should be installed. */
  public Path packageDir (String name) throws IOException {
    return packagesDir().resolve(name);
  }

  /** Returns all currently installed packages. */
  public Iterable<Package> packages () {
    return _pkgs.values();
  }

  /** Returns the package named {@code name}, if any. */
  public Optional<Package> packageByName (String name) {
    // TODO: map packages by name?
    for (Package pkg : packages()) if (pkg.name.equals(name)) return Optional.of(pkg);
    return Optional.empty();
  }

  /** Returns the package identified by {@code source}, if any. */
  public Optional<Package> packageBySource (Source source) {
    return Optional.ofNullable(_pkgs.get(source));
  }

  /** Returns a list of {@code pkg}'s transitive module dependencies. The list will be ordered such
    * that each package will appear later in the list than all packages on which it depends. Note:
    * {@code pkg} is included at the end of the list. */
  public List<Package> packageDepends (Package pkg) {
    LinkedHashMap<Source,Package> pkgs = new LinkedHashMap<>();
    addPackageDepends(pkgs, pkg);
    return new ArrayList<>(pkgs.values());
  }

  public void init () throws IOException {
    // resolve all packages in our packages directory (TODO: use cache if this is too slow)
    Files.walkFileTree(packagesDir(), FOLLOW_LINKS, MAX_PKG_DEPTH, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult preVisitDirectory (Path dir, BasicFileAttributes attrs) {
        Path pkgFile = dir.resolve(Package.FILE);
        if (!Files.exists(pkgFile)) return FileVisitResult.CONTINUE; // descend into subdirs
        addPackage(pkgFile);
        return FileVisitResult.SKIP_SUBTREE; // stop descending
      }
    });
  }

  public boolean addPackage (Path pkgFile) {
    try {
      Package pkg = new Package(pkgFile);
      // log any errors noted when resolving this package info
      if (!pkg.errors.isEmpty()) {
        Log.log("ERRORS in " + pkg.root + "/package.scaled:");
        for (String error : pkg.errors) Log.log("- " + error);
      }
      _pkgs.put(pkg.source, pkg);
      if (observer != null) observer.packageAdded(pkg);
      return true;
    } catch (Exception e) {
      Log.log("Unable to process package: "+ pkgFile, e);
      return false;
    }
  }

  private void addPackageDepends (LinkedHashMap<Source,Package> pkgs, Package pkg) {
    // stop if we've already added this package's depends
    if (pkgs.containsKey(pkg.source)) return;
    // add all packages on which any of this package's modules depend
    for (Module mod : pkg.modules()) {
      for (Depend dep : mod.depends) if (dep.isSource()) {
        Source psrc = ((Source)dep.id).packageSource();
        Package dpkg = _pkgs.get(psrc);
        if (dpkg == null) Log.log("Missing depend!", "mod", mod.source, "dep", dep.id);
        else if (dpkg != pkg) addPackageDepends(pkgs, dpkg);
      }
    }
    // then add this package
    pkgs.put(pkg.source, pkg);
  }

  private Path locateMetaDir () {
    // if our metadir has been overridden, use the specified value
    if (Props.scaledHome != null) return Paths.get(Props.scaledHome);

    Path homeDir = Paths.get(Props.userHome);
    // if we're on a Mac, put things in ~/Library/Application Support/Scaled
    Path appSup = homeDir.resolve("Library").resolve("Application Support");
    if (Files.exists(appSup)) return appSup.resolve("Scaled");
    // otherwise use ~/.scaled (TODO: we can probably do better on Windows)
    else return homeDir.resolve(".scaled");
  }

  private final Map<Source,Package> _pkgs = new HashMap<>();

  private static final Set<FileVisitOption> FOLLOW_LINKS = Collections.singleton(
    FileVisitOption.FOLLOW_LINKS);
  private static final int MAX_PKG_DEPTH = 6;

  // UGLY HACK ALERT: these dependencies are shared by all modules rather than duplicated for each
  // module; dependencies that show up in the public APIs of unrelated modules must come from the
  // same classloader; we cannot allow those dependencies to be duplicated by each module
  // classloader lest everything blow up when another module tries to use two or more of the
  // duplicators; right now only scala-library meets this criterion, but other depends will likely
  // join this club once we better support other JVM languages
  private static final Map<String,Set<String>> SYSTEM_DEPS = new HashMap<>();
  static {
    SYSTEM_DEPS.put("org.scala-lang", new HashSet<String>(Arrays.asList("scala-library")));
  }
}

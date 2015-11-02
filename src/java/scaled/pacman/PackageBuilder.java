//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handles the compilation of a package's code.
 */
public class PackageBuilder {

  public PackageBuilder (PackageRepo repo, Package pkg) {
    _repo = repo;
    _pkg = pkg;
  }

  /** Cleans out the build results directory for all modules in this package. */
  public void clean () throws IOException {
    for (Module mod : _pkg.modules()) {
      Filez.deleteAll(mod.classesDir());
    }
  }

  /** Cleans and builds all modules in this package. */
  public void build () throws IOException {
    for (Module mod : _pkg.modules()) build(mod);
  }

  /** Cleans and builds any modules in this package which have source files that have been modified
    * since the previous build. */
  public boolean rebuild () throws IOException {
    boolean rebuilt = false;
    for (Module mod : _pkg.modules()) rebuilt = rebuild(mod) || rebuilt;
    return rebuilt;
  }

  protected void build (Module mod) throws IOException {
    String what = mod.pkg.name;
    if (!mod.isDefault()) what += "#" + mod.name;
    Log.log("Building " + what + "...");

    // clear out and (re)create (if needed), the build output directory
    Filez.deleteAll(mod.classesDir());
    Files.createDirectories(mod.classesDir());

    // if a resources directory exists, copy that over
    Path rsrcDir = mod.resourcesDir();
    if (Files.exists(rsrcDir)) Filez.copyAll(rsrcDir, mod.classesDir());

    // now build whatever source we find in the project
    Map<String,Path> srcDirs = mod.sourceDirs();

    // if we have scala sources, use scalac to build scala+java code
    Path scalaDir = srcDirs.get("scala");
    Path javaDir = srcDirs.get("java");
    Path kotlinDir = srcDirs.get("kt");
    // compile scala first in case there are java files that depend on scala's; scalac does some
    // fiddling to support mixed compilation but it doesn't generate bytecode for .javas
    if (scalaDir != null) buildScala(mod, scalaDir, javaDir);
    // TODO: should we compile .kt before .java or after?
    if (kotlinDir != null) buildKotlin(mod, kotlinDir);
    if (javaDir != null) buildJava(mod, javaDir, scalaDir != null);
    // TODO: moar languages!

    // finally jar everything up
    createJar(mod.classesDir(), mod.moduleJar());
  }

  protected boolean rebuild (Module mod) throws IOException {
    Path moduleJar = mod.moduleJar();
    long lastBuild = Files.exists(moduleJar) ? Files.getLastModifiedTime(moduleJar).toMillis() : 0L;
    if (!Filez.existsNewer(lastBuild, mod.mainDir())) return false;
    build(mod);
    return true;
  }

  protected void buildScala (Module mod, Path scalaDir, Path javaDir) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(findJavaHome().resolve("bin").resolve("java").toString());

    // find out what version of scala-library is in our depends
    Depends deps = mod.depends(_repo.resolver);
    String scalaVers = deps.findVersion("org.scala-lang:scala-library");
    if (scalaVers == null) scalaVers = "2.11.7";

    // use scala-compiler of the same version
    String scalacId = "org.scala-lang:scala-compiler:" + scalaVers;
    cmd.add("-cp");
    cmd.add(classpathToString(_repo.mvn.resolve(RepoId.parse(scalacId)).values()));
    cmd.add("scala.tools.nsc.Main");

    cmd.add("-d"); cmd.add(mod.root.relativize(mod.classesDir()).toString());
    cmd.addAll(mod.pkg.scopts);
    List<Path> cp = buildClasspath(mod, deps);
    if (!cp.isEmpty()) { cmd.add("-classpath"); cmd.add(classpathToString(cp)); }
    if (javaDir != null) addSources(mod.root, javaDir, ".java", cmd);
    addSources(mod.root, scalaDir, ".scala", cmd);

    Exec.exec(mod.root, cmd).expect(0, "Scala build failed.");
  }

  protected void buildJava (Module mod, Path javaDir, boolean multiLang) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(findJavaHome().resolve("bin").resolve("javac").toString());

    cmd.addAll(mod.pkg.jcopts);
    Path target = mod.root.relativize(mod.classesDir());
    cmd.add("-d"); cmd.add(target.toString());
    List<Path> cp = buildClasspath(mod, mod.depends(_repo.resolver));
    // if we're compiling multiple languages, we need to add the target directory to our classpath
    // because we may have Java source files that depend on classes compiled by the other language
    if (multiLang) cp.add(0, target);
    if (!cp.isEmpty()) { cmd.add("-cp"); cmd.add(classpathToString(cp)); }
    addSources(mod.root, javaDir, ".java", cmd);

    Exec.exec(mod.root, cmd).expect(0, "Java build failed.");
  }

  protected void buildKotlin (Module mod, Path ktDir) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(findJavaHome().resolve("bin").resolve("java").toString());

    // find out what version of kotlin-library is in our depends
    Depends deps = mod.depends(_repo.resolver);
    String kotlinVers = deps.findVersion("org.jetbrains.kotlin:kotlin-stdlib");
    if (kotlinVers == null) kotlinVers = "1.0.0-beta-1038";

    // use kotlin-compiler of the same version
    String kotlincId = "org.jetbrains.kotlin:kotlin-compiler:" + kotlinVers;
    cmd.add("-cp");
    cmd.add(classpathToString(_repo.mvn.resolve(RepoId.parse(kotlincId)).values()));
    cmd.add("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");

    // cmd.addAll(mod.pkg.ktcopts);
    Path target = mod.root.relativize(mod.classesDir());
    cmd.add("-d"); cmd.add(target.toString());
    List<Path> cp = buildClasspath(mod, mod.depends(_repo.resolver));
    /* TODO: needed?
    // if we're compiling multiple languages, we need to add the target directory to our classpath
    // because we may have Java source files that depend on classes compiled by the other language
    if (multiLang) cp.add(0, target);
    */
    if (!cp.isEmpty()) { cmd.add("-cp"); cmd.add(classpathToString(cp)); }
    addSources(mod.root, ktDir, ".kt", cmd);

    Exec.exec(mod.root, cmd).expect(0, "Kotlin build failed.");
  }

  protected void createJar (Path sourceDir, Path targetJar) throws IOException {
    // if the old jar file exists, move it out of the way; this reduces the likelihood that we'll
    // cause a JVM to crash by truncating and replacing a jar file out from under it
    if (Files.exists(targetJar)) {
      Path oldJar = targetJar.resolveSibling("old-"+targetJar.getFileName());
      Files.move(targetJar, oldJar, StandardCopyOption.REPLACE_EXISTING);
    }
    List<String> cmd = new ArrayList<>();
    cmd.add("jar");
    cmd.add("-cf");
    cmd.add(targetJar.toString());
    cmd.add(".");
    Exec.exec(sourceDir, cmd).expect(0, "Jar creation failed.");
  }

  protected void addSources (Path root, Path dir, String suff, List<String> into) throws IOException {
    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult visitFile (Path file, BasicFileAttributes attrs)
      throws IOException {
        // TODO: allow symlinks to source files? that seems wacky...
        if (attrs.isRegularFile() && file.getFileName().toString().endsWith(suff)) {
          into.add(root.relativize(file).toString());
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }

  protected List<Path> buildClasspath (Module mod, Depends deps) {
    if (!deps.missingDeps.isEmpty()) {
      Log.log(mod + " has missing depends:");
      for (Depend.MissingId id : deps.missingDeps) Log.log(id.toString());
      throw new IllegalStateException(mod + " has missing depends");
    }
    List<Path> cp = deps.classpath();
    cp.remove(mod.classesDir());
    return cp;
  }

  protected String classpathToString (Iterable<Path> paths) {
    StringBuilder sb = new StringBuilder();
    for (Path path : paths) {
      if (sb.length() > 0) sb.append(Props.pathSep);
      sb.append(path);
    }
    return sb.toString();
  }

  protected Path findJavaHome () throws IOException {
    Path jreHome = Paths.get(Props.javaHome);
    Path javaHome = jreHome.getParent();
    if (isJavaHome(javaHome)) return javaHome;
    if (isJavaHome(jreHome)) return jreHome;
    throw new IllegalStateException("Unable to find java in " + jreHome + " or " + javaHome);
  }

  protected boolean isJavaHome (Path javaHome) {
    Path binDir = javaHome.resolve("bin");
    return Files.exists(binDir.resolve("java")) || Files.exists(binDir.resolve("java.exe"));
  }

  protected final PackageRepo _repo;
  protected final Package _pkg;
}

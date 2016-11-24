//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
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

/**
 * Ensures that the {@code mfetcher} jar file is downloaded, Pacman is checked out and built, sets
 * up a classpath, then invokes the {@link Pacman} tool. This class takes care not to reference any
 * {@code Pacman} classes so that it can upgrade and build the Pacman code and subsequently run it
 * without linker madness.
 */
public class Bootstrap {

  static final String MFETCHER_VERS = "1.0.5";
  static final long   MFETCHER_SIZE = 1808704;

  static final String MCENTRAL_URL = "https://repo1.maven.org/maven2/";
  static final String PACMAN_URL = "https://github.com/scaled/pacman.git";
  static final String PACMAN_CLASS = "scaled.pacman.Pacman";

  static final Path USER_HOME = Paths.get(System.getProperty("user.home"));
  static final Path JAVA_HOME = findJavaHome();

  static boolean DEBUG = false;
  static void debug (String message) { if (DEBUG) System.err.println("▸ " + message); }
  static void note (String message) { System.err.println("✓︎ " + message); }
  static void warn (String message) { System.err.println("✘ " + message); }
  static void fail (String message) { warn(message); System.exit(255); }

  static class Args {
    public List<String> sysPropArgs = new ArrayList<>();
    public List<String> jvmArgs = new ArrayList<>();
    public List<String> appArgs = new ArrayList<>();
  }

  public static void main (String[] args) throws Throwable {
    // parse our command line arguments (do this early so that we honor -d ASAP)
    Args pargs = parseArgs(args);
    debug("Java home: " + JAVA_HOME);
    debug("User home: " + USER_HOME);
    // determine where Scaled is (or should be) installed
    Path scaledHome = findScaledHome();
    debug("Scaled home: " + scaledHome);
    // download mfetcher jar depend if needed
    Path mfetcherJar = resolveMfetcher();
    // check out Pacman if needed
    Path pacmanRoot = checkoutPacman(scaledHome);
    // build Pacman if needed
    Path pacmanJar = buildPacman(pacmanRoot, mfetcherJar);

    // on Windows we need to copy pacman's module.jar to a temporary file to avoid freakout when
    // pacman tries to rebuild itself; yay Windows (also no Props here because we're in Bootstrap)
    if (System.getProperty("os.name").toLowerCase().contains("windows")) {
      File tempJar = File.createTempFile("pacman", ".jar", pacmanJar.getParent().toFile());
      tempJar.deleteOnExit();
      Path runtimeJar = tempJar.toPath();
      Files.copy(pacmanJar, runtimeJar, StandardCopyOption.REPLACE_EXISTING);
      pacmanJar = runtimeJar;
    }

    // if we have any JVM arguments, we need to fork, otherwise we can reuse this JVM
    if (pargs.jvmArgs.isEmpty()) {
      // set any requested system properties
      for (String sysProp : pargs.sysPropArgs) {
        // trim -D and split foo=bar on =
        String[] kv = sysProp.substring(2).split("=", 2);
        String key = kv[0], value = (kv.length == 2) ? kv[1] : "true";
        debug("Setting '" + key + "' sysprop to '" + value + "'.");
        System.setProperty(key, value);
      }

      // create a classloader with pacman's jar and the mfetcher dependency
      URL[] classpath = new URL[] { pacmanJar.toUri().toURL(), mfetcherJar.toUri().toURL() };
      debug("Launching Pacman in same JVM. Classpath:");
      for (URL jar : classpath) debug("  " + jar);
      // the defautl parent classloader is the current classloader, but we don't want that as it may
      // contain pacman classes; instead we get *our* parent classloader (the system loader) and use
      // that as the parent so that nothing in the bootstrap jar is visible to pacman
      ClassLoader parent = Bootstrap.class.getClassLoader().getParent();
      URLClassLoader loader = new URLClassLoader(classpath, parent);

      // resolve the Pacman class, the main method, and call it
      try {
        Class<?> pacmanClass = loader.loadClass(PACMAN_CLASS);
        Method main = pacmanClass.getMethod("main", String[].class);
        main.invoke(null, (Object)pargs.appArgs.toArray(new String[0]));

      } catch (ClassNotFoundException cnfe) {
        fail("Failed to load " + PACMAN_CLASS + ": " + cnfe + "\n" +
             "Classpath: " + Arrays.asList(classpath));
      } catch (NoSuchMethodException nsme) {
        fail("Failed to find " + PACMAN_CLASS + ".main(String[]): " + nsme);
      } catch (IllegalAccessException iae) {
        fail("Failed to invoke " + PACMAN_CLASS + ".main(): " + iae);
      } catch (InvocationTargetException ite) {
        throw ite.getCause();
      }

    // otherwise we have to fork a new JVM and pass all the args thereto
    } else {
      Path binJava = JAVA_HOME.resolve("bin").resolve("java");
      List<String> command = new ArrayList<>();
      command.add(binJava.toString());
      command.add("-classpath");
      command.add(pacmanJar + ":" + mfetcherJar);
      command.addAll(pargs.jvmArgs);
      command.addAll(pargs.sysPropArgs);
      command.add(PACMAN_CLASS);
      command.addAll(pargs.appArgs);
      new ProcessBuilder(command).inheritIO().start();
    }
  }

  static Path findJavaHome () {
    Path javaHome = Paths.get(System.getProperty("java.home"));
    // java.home may be JDK_HOME/jre
    if (Files.exists(javaHome.getParent().resolve("release"))) {
      return javaHome.getParent();
    }
    // otherwise hopefully it points to the JDK home, so use it as is
    return javaHome;
  }

  static Path findScaledHome () {
    // if a special home was specified via envvar, use it
    String scaledHomeEnv = System.getenv("SCALED_HOME");
    if (scaledHomeEnv != null) {
      Path scaledHome = Paths.get(scaledHomeEnv);
      if (Files.exists(scaledHome)) return scaledHome;
      fail("SCALED_HOME references non-existent directory: " + scaledHomeEnv);
    }
    // if we're on a Mac, use Library/Application Support/Scaled
    Path appsup = USER_HOME.resolve("Library").resolve("Application Support");
    if (Files.exists(appsup)) return appsup.resolve("Scaled");
    // if we're on (newish) Windows, use AppData/Local
    Path apploc = USER_HOME.resolve("AppData").resolve("Local");
    if (Files.exists(apploc)) return apploc.resolve("Scaled");
    // otherwise use ~/.scaled (where ~ is user.home)
    return USER_HOME.resolve(".scaled");
  }

  static Path resolveMfetcher () throws IOException { // TODO: exn handling?
    String mfetcherJar = "mfetcher-" + MFETCHER_VERS + ".jar";
    String mfetcherPath = "com/samskivert/mfetcher/" + MFETCHER_VERS + "/" + mfetcherJar;

    Path m2root = USER_HOME.resolve(".m2").resolve("repository");
    Path mfetcher = m2root.resolve(mfetcherPath);
    debug("Checking: " + mfetcher);

    String reason = null;
    if (!Files.exists(mfetcher)) reason = "file missing";
    else {
      long size = Files.size(mfetcher);
      if (size != MFETCHER_SIZE) reason = "jar size mismatch (" +
        size + " != " + MFETCHER_SIZE + ")";
    }

    if (reason != null) {
      debug("Downloading " + mfetcherJar + " due to: " + reason);
      download(URI.create(MCENTRAL_URL + mfetcherPath), mfetcher);
    }

    return mfetcher;
  }

  static Path checkoutPacman (Path scaledHome) throws IOException {
    Path packagesDir = scaledHome.resolve("Packages");
    Path pacmanRoot = packagesDir.resolve("pacman");

    // if we see source files, then we've probably already checked it out successfully
    if (Files.exists(pacmanRoot.resolve("src").resolve("java").resolve("scaled").resolve("pacman").
                     resolve("Pacman.java"))) return pacmanRoot;
    // otherwise we need to check it out or resume an aborted checkout

    // if we have a .git/config file...
    if (Files.exists(pacmanRoot.resolve(".git").resolve("config"))) {
      // then just git pull/checkout inside the pacman project directory
      note("Repairing partial package manager checkout in " + pacmanRoot);
      exec(pacmanRoot, "git", "pull");
      exec(pacmanRoot, "git", "checkout", ".");
    } else {
      // otherwise git clone pacmana into the packages directory (creating it if needed)
      Files.createDirectories(packagesDir);
      note("Checking out Scaled package manager into " + pacmanRoot);
      exec(packagesDir, "git", "clone", PACMAN_URL);
    }

    return pacmanRoot;
  }

  static Path buildPacman (Path pacmanRoot, Path mfetcherJar) throws IOException {
    // we repeat ourselves here, because we don't want to trigger init of Props class
    if (Boolean.getBoolean("pacman.ignore_module_jar")) {
      // if we're not using module.jar files, just assume Pacman is built and ready to run
      debug("Using pacman/target/classes instead of module.jar, per request.");
      return pacmanRoot.resolve("target").resolve("classes");
    }

    // if we already have a built jar file, then we're good to go
    Path pacmanJar = pacmanRoot.resolve("target").resolve("module.jar");
    if (Files.exists(pacmanJar)) return pacmanJar;

    // create our build and classes directories (if needed)
    Path targetDir = pacmanRoot.resolve("target");
    Path classesDir = targetDir.resolve("classes");
    Files.createDirectories(classesDir);

    // enumerate all source files in src/java into target/pacman.sources
    Path sourceDir = pacmanRoot.resolve("src").resolve("java");
    List<String> sourceFiles = new ArrayList<>();
    Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
      public FileVisitResult visitFile (Path file, BasicFileAttributes attrs) throws IOException {
        if (!attrs.isDirectory() && file.getFileName().toString().endsWith(".java")) {
          sourceFiles.add(pacmanRoot.relativize(file).toString());
        }
        return FileVisitResult.CONTINUE;
      }
    });
    Path sourcesFile = targetDir.resolve("pacman.sources");
    Files.write(sourcesFile, sourceFiles,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

    // build the code
    note("Compiling Scaled package manager...");
    Path binJavac = JAVA_HOME.resolve("bin").resolve("javac");
    exec(pacmanRoot, binJavac.toString(),
         "-encoding", System.getProperty("file.encoding"),
         "-classpath", mfetcherJar.toString(),
         "-d", classesDir.toString(), "@target/pacman.sources");

    // finally create the module.jar file
    exec(classesDir, "jar", "-cf", targetDir.resolve("module.jar").toString(), ".");

    return pacmanJar;
  }

  static Args parseArgs (String[] args) {
    Args pargs = new Args();
    for (String arg : args) {
      // args that start with -D are JVM args (sysprop settings)
      if (arg.startsWith("-D")) pargs.sysPropArgs.add(arg);
      // -J-XXX indicates a JVM arg that we should pass through (minus the -J)
      else if (arg.startsWith("-J")) pargs.jvmArgs.add(arg.substring(2));
      // -d just means turn on debugging, which we do via a sysprop
      else if (arg.equals("-d")) {
        DEBUG = true;
        pargs.sysPropArgs.add("-Ddebug");
      }
      // everything else is an argument to the app
      else pargs.appArgs.add(arg);
    }
    return pargs;
  }

  static void download (URI remote, Path local) throws IOException {
    note("Downloading " + remote);
    note("  into " + local);
    Files.createDirectories(local.getParent());
    try (InputStream in = remote.toURL().openStream()) {
      Files.copy(in, local, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static void exec (Path cwd, String... cmdArgs) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(cmdArgs);
    pb.directory(cwd.toFile());
    pb.inheritIO();
    Process proc = pb.start();
    try {
      int rv = proc.waitFor();
      if (rv != 0) fail("Subproc " + Arrays.asList(cmdArgs) + " failed with return code " + rv);
    } catch (InterruptedException ie) {
      fail("Subproc " + Arrays.asList(cmdArgs) + " interrupted");
    }
  }
}

//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** The main command line entry point for the Scaled Package Manager. */
public class Pacman {

  public static String[] USAGE = {
    "Usage: spam <command>",
    "",
    "where <command> is one of:",
    "",
    "  build pkg-name [--deps]              cleans and builds pkg-name (and depends if --deps)",
    "  clean pkg-name [--deps]              cleans pkg-name (and its depends if --deps)",
    "  depends pkg-name#module              prints flattened depend list pkg-name#module",
    "  deptree pkg-name                     prints depend tree for (all modules in) pkg-name",
    "  info [pkg-name | --all]              prints detailed info on pkg-name (or all packages)",
    "  install [pkg-name | pkg-url]         installs package (by name or url) and its depends",
    "  list [--all]                         lists installed (or all) packages",
    "  rebuild [from-pkg-name]              cleans and rebuilds all installed packages",
    "                                       continues rebuild at from-pkg-name if supplied",
    "  refresh                              updates the package directory index",
    "  run pkg-name#module class [arg ...]  runs class from pkg-name#module with args",
    "  search text                          lists all packages in directory which match text",
    "  upgrade [pkg-name]                   upgrades package and its depends"
  };

  public static final Printer out = new Printer(System.out);
  public static final PackageRepo repo = new PackageRepo();
  public static final PackageDirectory index = new PackageDirectory();

  public static void main (String[] args) {
    if (args.length == 0) fail(USAGE);

    // create our package repository and grind our packages
    try { repo.init(); }
    catch (Exception e) { fail("Failed to create package repository: " + e.getMessage()); }

    // read our index (downloading the initial package if necessary)
    initIndex();

    // we'll introduce proper arg parsing later; for now KISS
    try {
      switch (args[0]) {
        case     "run": run(arg(args, 1), arg(args, 2), tail(args, 3)); break;
        case    "info": info(arg(args, 1)); break;
        case    "list": list(optarg(args, 1, "").equals("--all")); break;
        case   "build": build(arg(args, 1), optarg(args, 2, "").equals("--deps")); break;
        case   "clean": clean(arg(args, 1), optarg(args, 2, "").equals("--deps")); break;
        case  "search": search(optarg(args, 1, "")); break;
        case "depends": depends(arg(args, 1)); break;
        case "deptree": deptree(arg(args, 1)); break;
        case "install": install(tail(args, 1)); break;
        case "rebuild": buildAll(optarg(args, 1, "")); break;
        case "refresh": refresh(); break;
        case "upgrade": upgrade(arg(args, 1)); break;
        default: fail(USAGE); break;
      }
    } catch (MissingArgException mae) {
      fail(usageFor(args[0]));
    }
  }

  private static String usageFor (String cmd) {
    String prefix = "  " + cmd;
    for (String usage : USAGE) {
      if (usage.startsWith(prefix)) return "Usage: spam " + usage.substring(2);
    }
    return "Usage: spam " + cmd + " <usage unknown>";
  }

  private static final String IDX_GIT_URL = "https://github.com/scaled/scaledex.git";
  private static final String IDX_PKG_NAME = "scaledex";
  private static void initIndex () {
    try {
      Path idir = repo.packageDir(IDX_PKG_NAME);
      if (!Files.exists(idir)) {
        System.out.println("* Fetching Scaled package directory...");
        VCSDriver.get(Source.VCS.GIT).checkout(new URI(IDX_GIT_URL), idir);
      }
      index.init(idir);
    } catch (Exception e) {
      Log.log("Error inititalizing package directory, 'search' and 'install' by name " +
              "are not going to work.", "error", e);
    }
  }

  private static void list (boolean all) {
    List<String[]> info = new ArrayList<>();
    for (Package pkg : repo.packages()) info.add(tuple(pkg.name, pkg.descrip));
    info.sort((i1, i2) -> i1[0].compareTo(i2[0]));
    if (!info.isEmpty()) info.add(0, tuple("Installed:", ""));
    if (all) {
      if (!info.isEmpty()) info.add(tuple("", ""));
      info.add(tuple("Not installed:", ""));
      for (PackageDirectory.Entry ent : index.entries) {
        if (!repo.packageBySource(ent.source).isPresent()) info.add(tuple(ent.name, ent.descrip));
      }
    }
    out.printCols(info, "No packages found.");
  }

  private static void search (String text) {
    String ltext = text.toLowerCase();
    List<String[]> info = new ArrayList<>();
    for (PackageDirectory.Entry entry : index.entries) {
      if (entry.matches(ltext)) {
        String name = entry.name;
        if (repo.packageBySource(entry.source).isPresent()) name += " [*]";
        info.add(tuple(name, entry.descrip));
      }
    }
    out.printCols(info, "No matches.");
    if (!info.isEmpty()) out.println("[*] indicates installed package");
  }

  private static void refresh () {
    out.println("Refreshing Scaled package index...");
    try { VCSDriver.get(Source.VCS.GIT).update(repo.packageDir(IDX_PKG_NAME)); }
    catch (Exception e) { fail("Refresh failed", e); }
  }

  private static void install (String... whences) {
    for (String whence : whences) {
      // see if this is a known package name
      PackageDirectory.Entry entry = index.byName.get(whence);
      if (entry != null) install(entry.source);
      // if this looks like a URL, try checking it out by URL
      else if (whence.contains(":")) {
        try { install(Source.parse(whence)); }
        catch (Exception e) { fail("Cannot install '" + whence + "'", e); }
      }
      else fail("Unknown package '" + whence + "'");
    }
  }

  private static void install (Source source) {
    if (repo.packageBySource(source).isPresent()) fail(
      "Package already installed: " + source + "\n" +
      "Use 'spam upgrade' to upgrade the package if desired.");
    try {
      new PackageOp(repo).install(source);
      out.println("Installation complete!");
    } catch (Exception e) { fail("Install failed", e); }
  }

  private static void upgrade (String pkgName) {
    onPackage(pkgName, pkg -> {
      try {
        new PackageOp(repo).upgrade(pkg);
        out.println("Upgrade complete!");
      } catch (Exception e) { fail("Upgrade failed", e); }
    });
  }

  private static void info (String pkgName) {
    if (!pkgName.equals("--all")) onPackage(pkgName, Pacman::printInfo);
    else for (Package pkg : repo.packages()) {
      out.println("------------------------------------------------------------");
      printInfo(pkg);
      out.println("");
    }
  }

  private static void printInfo (Package pkg) {
    out.println("Package: "+ pkg.name);
    out.printCols(Arrays.asList(tuple("Install:", pkg.root.toString()),
                                tuple("Source:",  pkg.source.toString()),
                                tuple("Version:", pkg.version),
                                tuple("Web URL:", pkg.weburl),
                                tuple("Modules:", pkg.modules().toString()),
                                tuple("Descrip:", pkg.descrip)), "", 1);
  }

  private static void deptree (String pkgName) {
    onPackage(pkgName, pkg -> {
      for (Module mod : pkg.modules()) {
        mod.depends(repo.resolver).dump(System.out, "", new HashSet<>());
      }
    });
  }

  private static void depends (String pkgMod) {
    onModule(pkgMod, mod -> {
      for (Depend.Id id : mod.depends(repo.resolver).flatten()) {
        out.println(id);
      }
    });
  }

  private static void buildAll (String pkgName) {
    List<Package> toBuild = repo.topoPackages();
    int[] procsToThreads = { 1, 1, 1, 2, 2, 3, 4, 5, 6 };
    int procs = Math.min(Runtime.getRuntime().availableProcessors(), procsToThreads.length-1);
    int threads = procsToThreads[procs];
    Log.log("Building up to " + threads + " packages in parallel.");

    class Builder {
      private CountDownLatch done = new CountDownLatch(threads);
      private Set<Source> built = new HashSet<>();
      private List<String> failMsgs = new ArrayList<>();
      private List<Exception> failErrs = new ArrayList<>();

      private synchronized Package getNext () {
        if (done()) return null;

        Package next = toBuild.remove(0);
        while (!next.dependsSatisfied(built)) {
          debug("Waiting for depends: " + next.name);
          try {
            wait();
          } catch (InterruptedException ie) {
            fail("Interrupted waiting to build " + next.root);
          }
          // if another build failed while we were waiting, abandon ship
          if (!failMsgs.isEmpty()) {
            return null;
          }
        }
        return next;
      }

      private synchronized boolean done () {
        return toBuild.isEmpty() || !failMsgs.isEmpty();
      }

      private synchronized void noteBuilt (Package pkg) {
        built.add(pkg.source);
        notifyAll();
      }

      private synchronized void noteFailed (Package pkg, Exception err) {
        failMsgs.add("Failure invoking 'build' in: " + pkg.root);
        failErrs.add(err);
        notifyAll();
      }

      private void runThread () {
        Package next;
        while ((next = getNext()) != null) {
          try {
            new PackageBuilder(repo, next).build();
            noteBuilt(next);
          } catch (Exception e) {
            noteFailed(next, e);
          }
        }
        done.countDown();
      }

      public synchronized void noteSkipped (Package pkg) {
        Log.log("Skipping " + pkg.name + "...");
        built.add(pkg.source);
      }

      public void run () {
        for (int ii = 0; ii < threads; ii++) {
          new Thread() { public void run () { runThread(); }}.start();
        }

        try {
          done.await();
        } catch (InterruptedException ie) {
          fail("Interrupted waiting for build to complete.");
        }

        if (!failMsgs.isEmpty()) {
          for (int ii = 0; ii < failMsgs.size(); ii++) {
            System.err.println(failMsgs.get(ii));
            failErrs.get(ii).printStackTrace(System.err);
          }
          System.exit(255);
        }
      }
    }

    Builder builder = new Builder();
    if (!pkgName.equals("")) {
      while (!toBuild.isEmpty() && !toBuild.get(0).name.equals(pkgName)) {
        builder.noteSkipped(toBuild.remove(0)); // for depends tracking
      }
    }
    builder.run();
  }

  private static void build (String pkgName, boolean deps) {
    onPackage(pkgName, pkg -> {
      for (Package bpkg : packageOrDeps(pkg, deps)) {
        try { new PackageBuilder(repo, bpkg).build(); }
        catch (Exception e) {
          fail("Failure invoking 'build' in: " + bpkg.root, e);
        }
      }
    });
  }

  private static void clean (String pkgName, boolean deps) {
    onPackage(pkgName, pkg -> {
      for (Package bpkg : packageOrDeps(pkg, deps)) {
        try { new PackageBuilder(repo, bpkg).clean(); }
        catch (Exception e) {
          fail("Failure invoking 'clean' in: " + bpkg.root, e);
        }
      }
    });
  }

  private static List<Package> packageOrDeps (Package pkg, boolean deps) {
    return deps ? repo.packageDepends(pkg) : Collections.singletonList(pkg);
  }

  private static void run (String pkgMod, String classname, String[] args) {
    onModule(pkgMod, mod -> {
      try {
        ModuleLoader loader = mod.loader(repo.resolver);
        if (Props.debug) {
          debug("Running " + pkgMod + " " + classname + " " + Arrays.asList(args));
          loader.dump("  ");
        }
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> clazz = loader.loadClass(classname);
        clazz.getMethod("main", String[].class).invoke(null, (Object)args);
      } catch (Exception e) {
        e.printStackTrace(System.err);
      }
    });
  }

  private static void onPackage (String name, Consumer<Package> fn) {
    Optional<Package> po = repo.packageByName(name);
    if (po.isPresent()) fn.accept(po.get());
    else fail("Unknown package: " + name);
  }

  private static void onModule (String pkgMod, Consumer<Module> fn) {
    int hidx = pkgMod.indexOf("#");
    String pkgName = (hidx == -1) ? pkgMod : pkgMod.substring(0, hidx);
    String modName = (hidx == -1) ? Module.DEFAULT : pkgMod.substring(hidx+1);
    onPackage(pkgName, pkg -> {
      Module mod = pkg.module(modName);
      if (mod == null) fail("Unknown module: " + modName + " in package: " + pkgName);
      else fn.accept(mod);
    });
  }

  private static String[] tuple (String... strs) {
    return strs;
  }

  private static String arg (String[] args, int idx) {
    if (args.length <= idx) throw new MissingArgException();
    return args[idx];
  }

  private static String optarg (String[] args, int idx, String defval) {
    return (args.length > idx) ? args[idx] : defval;
  }

  private static String[] tail (String[] args, int from) {
    String[] rest = new String[args.length-from];
    System.arraycopy(args, from, rest, 0, rest.length);
    return rest;
  }

  static void debug (String message) { if (Props.debug) System.err.println("▸ " + message); }

  private static void fail (String... msgs) {
    for (String msg : msgs) System.err.println(msg);
    System.exit(255);
  }

  private static void fail (String msg, Throwable cause) {
    System.err.println(msg);
    cause.printStackTrace(System.err);
    System.exit(255);
  }

  private static class MissingArgException extends RuntimeException {}
}

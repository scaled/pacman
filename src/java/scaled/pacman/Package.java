//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Contains runtime metadata for an installed package. */
public class Package {

  /** The string {@code package.scaled} for all to share and enjoy. */
  public static final String FILE = "package.scaled";

  /** The default options passed to javac. */
  public static final List<String> DEFAULT_JCOPTS = Arrays.asList(
    "-source", "1.8", "-target", "1.8", "-Xlint:all");

  /** The default options passed to scalac. */
  public static final List<String> DEFAULT_SCOPTS = Arrays.asList(
    "-deprecation", "-feature");

  /** The root of this package, generally a directory. */
  public final Path root;

  public final Source source;
  public final String name;
  public final String version;
  public final String license;
  public final String weburl;
  public final String descrip;

  public final List<String> jcopts;
  public final List<String> scopts;
  public final List<Depend> depends;

  public final List<String> errors;

  /** Returns all modules contained in this package. These are returned topologically sorted, such
    * that any module which depends on another module in this package will show up later in the list
    * than the module on which it depends. */
  public Iterable<Module> modules () {
    List<Module> mods = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<Module> remain = new ArrayList<>(_modules.values());
    // repeatedly loop through our remaining modules, adding any modules whose entire dependency
    // set has been "seen"
    while (!remain.isEmpty()) {
      int had = remain.size();
      for (Iterator<Module> iter = remain.iterator(); iter.hasNext(); ) {
        Module mod = iter.next();
        if (seen.containsAll(mod.localDepends)) {
          seen.add(mod.name);
          mods.add(mod);
          iter.remove();
        }
      }
      // if we haven't moved at least one module into the seen set, we're hosed
      if (had == remain.size()) throw new IllegalStateException(
          "Cyclic inter-module dependencies in package: " + remain);
    }
    return mods;
  }

  /** Returns the module with name {@code name} or null. */
  public Module module (String name) {
    return _modules.get(name);
  }

  /** Returns whether all of this package's (package) dependencies are in {@code pkgs}. */
  public boolean dependsSatisfied (Set<Source> pkgs) {
    for (Module mod : modules()) {
      for (Depend dep : mod.depends) if (dep.isSource()) {
        Source psrc = ((Source)dep.id).packageSource();
        if (!psrc.equals(source) && !pkgs.contains(psrc)) {
          return false;
        }
      }
    }
    return true;
  }

  /** Creates a package info from the supplied `package.scaled` file.
    * The file is assumed to be in the top-level directory of the package in question. */
  public Package (Path file) throws IOException {
    this(file.getParent(), Files.readAllLines(file));
  }

  /** Creates a package info from the `package.scaled` contents in `lines`. */
  public Package (Path root, Iterable<String> lines) {
    this(root, new Config(lines));
  }

  public Package (Path root, Config cfg) {
    this.root = root;
    source  = cfg.resolve("source",  Config.SourceP);
    name    = cfg.resolve("name",    Config.StringP);
    version = cfg.resolve("version", Config.StringP);
    license = cfg.resolve("license", Config.StringP);
    weburl  = cfg.resolve("weburl",  Config.StringP); // todo UrlP
    descrip = cfg.resolve("descrip", Config.StringP);

    jcopts = new ArrayList<>(DEFAULT_JCOPTS);
    jcopts.addAll(cfg.resolve("jcopt", Config.StringListP));
    jcopts.addAll(cfg.resolve("jcopts", Config.WordsP));

    scopts = new ArrayList<>(DEFAULT_SCOPTS);
    scopts.addAll(cfg.resolve("scopt", Config.StringListP));
    scopts.addAll(cfg.resolve("scopts", Config.WordsP));

    depends = cfg.resolveDepends();
    List<String> mods = cfg.resolve("module", Config.StringListP);

    // we're done with the package config, so accumulate any errors
    errors = cfg.finish();

    // if we have a top-level source directory, add the default module
    if (Files.exists(root.resolve("src"))) {
      _modules.put(Module.DEFAULT, new Module(
        this, Module.DEFAULT, root, source, new Config(Collections.emptyList())));
    }

    // this will noop if no modules were defined, but we structure the code this way because we need
    // errors to be initialized before we parse our module configs
    for (String mname : mods) {
      // the default module is rooted at the top of the package tree
      Path mroot = root.resolve(mname);
      try {
        Config mcfg = new Config(Files.readAllLines(mroot.resolve("module.scaled")));
        _modules.put(mname, new Module(this, mname, mroot, source.moduleSource(mname), mcfg));
        errors.addAll(mcfg.finish());
      } catch (IOException ioe) {
        errors.add("Failed to parse module " + mname + ": " + ioe);
      }
    }
  }

  /** Returns sources for all packages on which any module in this package depends. */
  public Set<Source> packageDepends () {
    Set<Source> deps = new HashSet<>();
    for (Module mod : modules()) {
      for (Depend dep : mod.depends) if (dep.isSource()) {
        Source source = ((Source)dep.id).packageSource();
        if (!source.equals(this.source)) deps.add(source); // don't include self-depends
      }
    }
    return deps;
  }

  @Override public String toString () {
    return (" source=" + source  + "\n" +
            "   name=" + name    + "\n" +
            "version=" + version + "\n" +
            "license=" + license + "\n" +
            " weburl=" + weburl  + "\n" +
            "descrip=" + descrip + "\n" +
            "modules=" + _modules.keySet() + "\n" +
            " errors=" + errors);
  }

  private final Map<String,Module> _modules = new HashMap<>();
}

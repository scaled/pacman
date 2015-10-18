//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Represents the resolved dependencies for a module. These are split into (filtered) binary
 * dependencies and module dependencies. Due to the way pacman loads classes, a module must inherit
 * the exact binary dependencies of any of its module dependencies. This differs from a system like
 * Maven where a module could override the version of one of its inherited binary dependencies.
 *
 * <p>Thus, a module's expressed dependencies, which may include binary (Maven and System)
 * dependencies as well as dependencies on other pacman modules will be processed into a set of
 * binary dependencies which do not appear in the resolved dependencies of any of the module's
 * module dependees, and the list of module dependees.</p>
 */
public class Depends {

  /** Used to resolve module dependencies. */
  public static interface Resolver {
    /** Returns the module identified by {@code source}, if any. */
    Optional<Module> moduleBySource (Source source);

    /** Resolves the supplied Maven depends. */
    Map<RepoId,Path> resolve (List<RepoId> ids);

    /** Resolves the supplied system depend. */
    Path resolve (SystemId id);

    /** Returns true if {@code path} represents a system dependency. This is a primitive mechanism
      * to allow certain (non-module) dependencies to be shared in the runtime classloader graph
      * because that dependency's types show up in the public API of unrelated modules. The
      * canonical example of such a dependency is {@code org.scala-lang:scala-library}.
      *
      * <p>One limitation of this system is that system dependencies cannot themselves have
      * dependencies. This sufficies for our purposes thus far and keeps the lid on an unpleasantly
      * large and wiggly can of worms.</p>
      */
    boolean isSystem (RepoId id);

    /** Returns the classloader for the specified system dependency. */
    ClassLoader systemLoader (Path path);
  }

  /** The module whose dependencies we contain. */
  public final Module mod;

  /** The binary dependencies for this module, mapped to the {@link Depend.Id} from which they were
    * resolved (if any). A dependency will map to {@code null} if it is a transitive Maven
    * dependency that somehow resolved to a path which could not be reverse engineered back to a
    * Maven dependency. */
  public final Map<Path,Depend.Id> binaryDeps;

  /** The system dependencies for this module, mapped to the {@link Depend.Id} from which they were
    * resolved (if any). A dependency will map to {@code null} if it is a transitive Maven
    * dependency that somehow resolved to a path which could not be reverse engineered back to a
    * Maven dependency. */
  public final Map<Path,Depend.Id> systemDeps;

  /** Direct dependencies that were filtered from this module because they were already exported as a
    * transitive dependency of one of our other direct dependencies. */
  public final Map<Path,Depend.Id> filteredDeps;

  /** The module dependencies for this module. */
  public final List<Depends> moduleDeps;

  /** Contains any declared source dependencies that could not be resolved. */
  public final List<Depend.MissingId> missingDeps;

  public Depends (Module module, Resolver resolve) {
    this.mod = module;
    this.systemDeps = new HashMap<>();
    // use a linked hash map because we need to preserve iteration order for bindeps
    this.binaryDeps = new LinkedHashMap<>();
    this.filteredDeps = new HashMap<>();
    this.moduleDeps = new ArrayList<>();
    this.missingDeps = new ArrayList<>();

    List<RepoId> mvnIds = new ArrayList<>();
    List<SystemId> sysIds = new ArrayList<>();
    for (Depend dep : module.depends) {
      if (dep.scope == Depend.Scope.EXEC) continue; // omit exec depends
      if (dep.id instanceof RepoId) mvnIds.add((RepoId)dep.id);
      else if (dep.id instanceof SystemId) sysIds.add((SystemId)dep.id);
      else {
        Source depsrc = (Source)dep.id;
        // if we depend on a module in our same package, resolve it specially; this ensures that
        // when we're building a package prior to installing it, intrapackage depends are properly
        // resolved even though the package itself is not yet registered with this repo
        Optional<Module> dmod = !module.isSibling(depsrc) ? resolve.moduleBySource(depsrc) :
          Optional.ofNullable(module.pkg.module(depsrc.module()));
        if (dmod.isPresent()) moduleDeps.add(dmod.get().depends(resolve));
        else missingDeps.add(new Depend.MissingId(dep.id));
      }
    }

    // compute the transitive set of binary and system depends already handled by our module
    // dependencies; we'll omit those from our deps because we want to "inherit" them
    Set<Path> haveBinaryDeps = new HashSet<>();
    Set<String> haveSystemDeps = new HashSet<>();
    for (Depends dep : moduleDeps) dep.accumDeps(haveBinaryDeps, haveSystemDeps);

    // resolve our Maven depends; split them into system and (private) bindeps
    if (!mvnIds.isEmpty()) {
      for (Map.Entry<RepoId,Path> entry : resolve.resolve(mvnIds).entrySet()) {
        RepoId id = entry.getKey();
        Path path = entry.getValue();
        if (path == null) missingDeps.add(new Depend.MissingId(id));
        else if (resolve.isSystem(id)) {
          if (!haveSystemDeps.contains(id.stableId())) systemDeps.put(path, id);
          else filteredDeps.put(path, id);
        }
        else if (!haveBinaryDeps.contains(path)) binaryDeps.put(path, id);
        else filteredDeps.put(path, id);
      }
    }

    // resolve our System depends; system depends are always system
    for (SystemId id : sysIds) try {
      Path path = resolve.resolve(id);
      if (!haveSystemDeps.contains(id.stableId())) systemDeps.put(path, id);
      else filteredDeps.put(path, id);
    } catch (IllegalArgumentException e) {
      missingDeps.add(new Depend.MissingId(id));
    }
  }

  public void accumDeps (Set<Path> binary, Set<String> system) {
    binary.addAll(binaryDeps.keySet());
    for (Depend.Id sdep : systemDeps.values()) system.add(sdep.stableId());
    for (Depends dep : moduleDeps) dep.accumDeps(binary, system);
  }

  public List<Path> classpath () {
    return new ArrayList<>(buildClasspath(new LinkedHashSet<>(), true));
  }

  public List<Path> dependClasspath () {
    return new ArrayList<>(buildClasspath(new LinkedHashSet<>(), false));
  }

  public List<Depend.Id> flatten () {
    return new ArrayList<>(buildFlatIds(new LinkedHashSet<>(), false));
  }

  /**
   * Returns the version for the depend with {@code stableId} in this modules transitive dependency
   * set, or null if it does not exist therein.
   */
  public String findVersion (String stableId) {
    for (Depend.Id dep : binaryDeps.values()) {
      if (dep.stableId().equals(stableId)) return dep.version();
    }
    for (Depend.Id dep : systemDeps.values()) {
      if (dep.stableId().equals(stableId)) return dep.version();
    }
    for (Depends mdeps : moduleDeps) {
      String version = mdeps.findVersion(stableId);
      if (version != null) return version;
    }
    return null;
  }

  public void dump (PrintStream out, String indent, Set<Source> seen) {
    if (seen.add(mod.source)) {
      out.println(indent + mod.source);
      out.println(indent + "= " + mod.classpath());
      String dindent = indent + "- ";
      for (Path path : binaryDeps.keySet()) out.println(dindent + path);
      for (Path path : systemDeps.keySet()) out.println(dindent + path + " (system)");
      for (Path path : filteredDeps.keySet()) out.println(dindent + path + " (filtered)");
      for (Depends deps : moduleDeps) deps.dump(out, dindent, seen);
    } else {
      out.println(indent + "(*) " + mod.source);
    }
  }

  private Set<Path> buildClasspath (Set<Path> into, boolean self) {
    if (!into.contains(mod.classpath())) {
      if (self) into.add(mod.classpath());
      into.addAll(binaryDeps.keySet());
      into.addAll(systemDeps.keySet());
      for (Depends dep : moduleDeps) dep.buildClasspath(into, true);
    }
    return into;
  }

  private Set<Depend.Id> buildFlatIds (Set<Depend.Id> into, boolean self) {
    if (!into.contains(mod.source)) {
      if (self) into.add(mod.source);
      into.addAll(binaryDeps.values());
      into.addAll(systemDeps.values());
      // we were unable to resolve them, but we can still report them
      into.addAll(missingDeps);
      for (Depends dep : moduleDeps) dep.buildFlatIds(into, true);
    }
    return into;
  }
}

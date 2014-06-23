//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

  /** The module whose dependencies we contain. */
  public final Module mod;

  /** The scope at which these dependencies were resolved (main or test). */
  public final Depend.Scope scope;

  /** The binary dependencies for this module, mapped to the {@link Depend.Id} from which they were
    * resolved (if any). A dependency will map to {@code null} if it is a transitive Maven
    * dependency that somehow resolved to a path which could not be reverse engineered back to a
    * Maven dependency. */
  public final Map<Path,Depend.Id> binaryDeps;

  /** The module dependencies for this module. */
  public final List<Depends> moduleDeps;

  public Depends (Module module, PackageRepo repo, boolean testScope) {
    List<RepoId> mvnIds = new ArrayList<>();
    List<SystemId> sysIds = new ArrayList<>();
    List<Depends> moduleDeps = new ArrayList<>();
    for (Depend dep : module.depends) {
      if (!dep.scope.include(testScope)) continue; // skip depends that don't match our scope
      if (dep.id instanceof RepoId) mvnIds.add((RepoId)dep.id);
      else if (dep.id instanceof SystemId) sysIds.add((SystemId)dep.id);
      else {
        Source depsrc = (Source)dep.id;
        // if we depend on a module in our same package, resolve it specially; this ensures that
        // when we're building a package prior to installing it, intrapackage depends are properly
        // resolved even though the package itself is not yet registered with this repo
        Optional<Module> dmod = !module.isSibling(depsrc) ? repo.moduleBySource(depsrc) :
          Optional.ofNullable(module.pkg.module(depsrc.module()));
        if (dmod.isPresent()) moduleDeps.add(dmod.get().depends(repo, testScope));
        else repo.log.log("Missing source depend", "owner", module.source, "source", depsrc);
      }
    }

    // use a linked hash map because we need to preserve iteration order
    LinkedHashMap<Path,Depend.Id> binDeps = new LinkedHashMap<>();
    // if the module has binary dependencies, resolve those and add them to binary deps
    if (!mvnIds.isEmpty() || !sysIds.isEmpty()) {
      // compute the transitive set of binary depends already handled by our module dependencies;
      // we'll omit those from our binary deps because we want to "inherit" them
      Set<Path> haveBinaryDeps = new HashSet<>();
      for (Depends dep : moduleDeps) dep.accumBinaryDeps(haveBinaryDeps);
      // resolve and add our Maven depends (reverse engineer the RepoId from the paths returned by
      // Capsule; I don't want to hack up Capsule to return other data and extracting Maven
      // coordinates from file path isn't particularly fiddly)
      for (Path path : repo.mvn.resolve(mvnIds)) {
        if (!haveBinaryDeps.contains(path)) binDeps.put(path, RepoId.fromPath(path));
      }
      // resolve and add our System depends
      for (SystemId sysId : sysIds) {
        Path path = repo.sys.resolve(sysId);
        if (!haveBinaryDeps.contains(path)) binDeps.put(path, sysId);
      }
    }

    this.mod = module;
    this.scope = testScope ? Depend.Scope.TEST : Depend.Scope.MAIN;
    this.binaryDeps = binDeps;
    this.moduleDeps = moduleDeps;
  }

  public void accumBinaryDeps (Set<Path> into) {
    into.addAll(binaryDeps.keySet());
    for (Depends dep : moduleDeps) dep.accumBinaryDeps(into);
  }

  public List<Path> classpath () {
    List<Path> cp = new ArrayList<>();
    Set<Source> seen = new HashSet<Source>();
    buildClasspath(cp, seen);
    return cp;
  }

  public List<Depend.Id> flatten () {
    List<Depend.Id> ids = new ArrayList<>();
    Set<Source> seen = new HashSet<Source>();
    buildFlatIds(ids, seen);
    return ids;
  }

  public void dump (PrintStream out, String indent, Set<Source> seen) {
    if (seen.add(mod.source)) {
      out.println(indent + mod.source);
      out.println(indent + "= " + mod.classesDir());
      String dindent = indent + "- ";
      for (Path path : binaryDeps.keySet()) out.println(dindent + path);
      for (Depends deps : moduleDeps) deps.dump(out, dindent, seen);
    } else {
      out.println(indent + "(*) " + mod.source);
    }
  }

  private void addFiltered (Set<Path> except, List<Path> source, Collection<Path> dest) {
    for (Path path : source) if (!except.contains(path)) dest.add(path);
  }

  private void buildClasspath (List<Path> cp, Set<Source> seen) {
    if (seen.add(mod.source)) {
      cp.add(mod.classesDir());
      cp.addAll(binaryDeps.keySet());
      for (Depends dep : moduleDeps) dep.buildClasspath(cp, seen);
    }
  }

  private void buildFlatIds (List<Depend.Id> ids, Set<Source> seen) {
    if (seen.add(mod.source)) {
      ids.add(mod.source);
      ids.addAll(binaryDeps.values());
      for (Depends dep : moduleDeps) dep.buildFlatIds(ids, seen);
    }
  }
}

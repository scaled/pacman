//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class PackageOp {

  public PackageOp (PackageRepo repo) {
    _repo = repo;
  }

  /** Installs the package referenced by {@code source} and all of its depends. */
  public void install (Source source) throws IOException {
    // create a temp directory into which we'll clone and build the package
    Path temp = Files.createTempDirectory(_repo.metaDir("Scratch"), "install");
    // delete this directory on JVM shutdown, if we haven't done it already
    Runtime.getRuntime().addShutdownHook(new Thread() { public void run () {
      try { Filez.deleteAll(temp); }
      catch (IOException e) { e.printStackTrace(System.err); }
    }});
    PackageFetcher pf = new PackageFetcher(_repo, source, temp);
    _repo.log.log("Cloning " + source + " into temp dir...");
    pf.checkout();

    // parse this package's depends and install those before building the package
    Package pkg = pf.readPackage();
    installDepends(pkg);

    // now that the depends are built and installed, we can build this package
    _repo.log.log("Building " + pkg.name + "...");
    PackageBuilder pb = new PackageBuilder(_repo, pkg);
    pb.build();

    // all went well, we can now move the package into place
    _repo.log.log("Installing " + source + " into Packages/" + pkg.name + "...");
    pf.install(pkg);
  }

  /** Upgrades the package referenced by {@code source} and all of its depends. */
  public void upgrade (Package pkg) throws IOException {
    // if we've already upgraded this package during this operation, don't do it again
    if (!_upgraded.add(pkg.source)) return;

    // update the VCS clone of this package's source tree
    PackageFetcher pf = new PackageFetcher(_repo, pkg.source, pkg.root);
    _repo.log.log("Updating " + pkg.source + "...");
    pf.update();

    // reparse this package's depends, install any new depends, upgrade any existing depends
    Package npkg = pf.readPackage();
    installDepends(npkg);

    // rebuild the package itself
    if (rebuild(npkg)) {
      // if we actually rebuilt anything, upgrade any packages that depend on this package
      Set<Package> updeps = new HashSet<>();
      for (Package dpkg : _repo.packages()) {
        if (dpkg.packageDepends().contains(pkg.source)) updeps.add(dpkg);
      }
      if (!updeps.isEmpty()) {
        _repo.log.log("Upgrading " + updeps.size() + " dependents on " + npkg.name + "...");
        // force all of our dependents to be rebuilt; this package may no longer be binary
        // compatible with its previous build
        _forceBuild.addAll(updeps);
        for (Package updep : updeps) upgrade(updep);
      }
    }
  }

  /** Ensures that all depends of this package have been installed and upgraded. */
  public void installDepends (Package pkg) throws IOException {
    for (Source source : pkg.packageDepends()) {
      Optional<Package> dpkg = _repo.packageBySource(source);
      // this package is not installed, install it
      if (!dpkg.isPresent()) install(source);
      // otherwise see if it needs upgrading
      else upgrade(dpkg.get());
    }
  }

  protected boolean rebuild (Package pkg) throws IOException {
    PackageBuilder pb = new PackageBuilder(_repo, pkg);
    if (!_forceBuild.contains(pkg)) return pb.rebuild();
    pb.build();
    return true;
  }

  protected final PackageRepo _repo;
  protected final Set<Source> _upgraded = new HashSet<>();
  protected final Set<Package> _forceBuild = new HashSet<>();
}

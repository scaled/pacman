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
    Log.log("Cloning " + source + " into temp dir...");
    pf.checkout();

    // parse this package's depends and install those before building the package
    Package pkg = pf.readPackage();
    installDepends(pkg);

    // now that the depends are built and installed, we can build this package
    Log.log("Building " + pkg.name + "...");
    PackageBuilder pb = new PackageBuilder(_repo, pkg);
    pb.build();

    // all went well, we can now move the package into place
    Log.log("Installing " + source + " into Packages/" + pkg.name + "...");
    pf.install(pkg);
  }

  /** Upgrades the package referenced by {@code source} and all of its depends. */
  public void upgrade (Package pkg) throws IOException {
    // if we've already upgraded this package during this operation, don't do it again
    if (!_upgraded.add(pkg.source)) return;

    // update the VCS clone of this package's source tree
    PackageFetcher pf = new PackageFetcher(_repo, pkg.source, pkg.root);
    Log.log("Updating " + pkg.source + "...");
    pf.update();

    // reparse this package's depends, install any new depends, upgrade any existing depends
    Package npkg = pf.readPackage();
    Set<Source> npdeps = npkg.packageDepends();
    npdeps.removeAll(_upgraded);
    if (!npdeps.isEmpty()) {
      Log.log("Updating " + npdeps.size() + " pkgs on which " + npkg.name + " depends...");
      installDepends(npkg);
    }

    // rebuild the package itself
    if (rebuild(npkg)) {
      // if we actually rebuilt anything, upgrade any packages that depend on this package
      Set<Package> updeps = new HashSet<>();
      for (Package dpkg : _repo.packages()) {
        if (dpkg.packageDepends().contains(pkg.source)) {
          // force this package to be rebuild (which may not yet have happened even if the package
          // is already in _upgraded)
          _forceBuild.add(dpkg.source);
          // omit this package from our forced upgrade list if it's already been upgraded
          if (!_upgraded.contains(dpkg.source)) updeps.add(dpkg);
        }
      }
      if (!updeps.isEmpty()) {
        Log.log("Upgrading " + updeps.size() + " pkgs which depend on " + npkg.name + "...");
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
    if (!_forceBuild.contains(pkg.source)) return pb.rebuild();
    pb.build();
    return true;
  }

  protected final PackageRepo _repo;
  protected final Set<Source> _upgraded = new HashSet<>();
  protected final Set<Source> _forceBuild = new HashSet<>();
}

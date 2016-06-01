//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Handles checking out and updating packages via external DVCS programs.
 */
public class PackageFetcher {

  public PackageFetcher (PackageRepo repo, Source source, Path pkgDir) {
    _repo = repo;
    _source = source;
    _pkgDir = pkgDir;
    _vcs = VCSDriver.get(source.vcs);
  }

  /** Loads and returns the package fetched by this fetcher. This assumes that {@code pkgDir} points
    * to a valid checkout of this project. Either construct a fetcher with an already valid package
    * or call {@link #checkout} prior to this call. */
  public Package readPackage () throws IOException {
    return new Package(_pkgDir.resolve(Package.FILE));
  }

  /** Checks out the this package into {@code pkgDir}. */
  public void checkout () throws IOException {
    if (_vcs.exists(_source.url, _pkgDir)) update();
    else _vcs.checkout(_source.url, _pkgDir);
  }

  /** Updates the VCS clone in {@code pkgDir}. */
  public void update () throws IOException {
    _vcs.fetch(_pkgDir);
    _vcs.update(_pkgDir);
  }

  /** Installs the fetche packaged into its proper location in the package repository. */
  public void install (Package pkg) throws IOException {
    Path target = _repo.packageDir(pkg.name);
    // windows can die in a fire
    if (Props.isWindows) {
      Filez.copyAll(_pkgDir, target);
    } else {
      Files.move(_pkgDir, target, StandardCopyOption.ATOMIC_MOVE);
    }
    _repo.addPackage(target.resolve(Package.FILE));
  }

  private final PackageRepo _repo;
  private final Source _source;
  private final Path _pkgDir;
  private final VCSDriver _vcs;
}

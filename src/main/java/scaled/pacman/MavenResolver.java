//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import capsule.DependencyManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class MavenResolver {

  public final DependencyManager capsule =
  new DependencyManager(RepoId.m2repo, null, false, false);

  public List<Path> resolve (List<RepoId> ids) {
    List<String> coords = new ArrayList<>();
    for (RepoId id : ids) coords.add(id.toCoord());
    // filter the resolved depends through a linked hashset, which eliminates duplicates while
    // preserving order
    return new ArrayList<Path>(new LinkedHashSet<Path>(capsule.resolveDependencies(coords, "jar")));
  }
}

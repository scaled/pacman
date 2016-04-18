//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfetcher.Coord;
import mfetcher.DependencyManager;
import impl.org.eclipse.aether.RepositoryEvent;
import impl.org.eclipse.aether.transfer.TransferEvent;

public class MavenResolver {

  public static final List<String> REPOS = Arrays.asList(
    "central", "http://repo.gradle.org/gradle/libs-releases-local/");

  public final DependencyManager depmgr = new DependencyManager(RepoId.m2repo, REPOS, false, false) {
    @Override protected void onRepositoryEvent (String method, RepositoryEvent event) {
      if (method.endsWith("Invalid") || method.endsWith("Missing")) {
        Log.log("MavenResolver." + method + " " + event);
      // } else if (method.equals("artifactResolved")) {
      //   Log.log("MavenResolver." + method + " " + event);
      }
    }
    @Override protected void onTransferEvent (String method, TransferEvent event) {
      if (method.endsWith("Corrupted") || method.endsWith("Failed") ||
          method.endsWith("Succeeded")) {
        Log.log("MavenResolver." + method + " " + event);
      }
    }
  };

  public Map<RepoId,Path> resolve (RepoId id) {
    return resolve(Arrays.asList(id));
  }

  public Map<RepoId,Path> resolve (List<RepoId> ids) {
    List<Coord> coords = new ArrayList<>();
    for (RepoId id : ids) coords.add(toCoord(id));
    Map<RepoId,Path> results = new LinkedHashMap<>();
    try {
      for (Map.Entry<Coord,Path> entry : depmgr.resolveDependencies(coords).entrySet()) {
        results.put(toRepoId(entry.getKey()), entry.getValue());
      }
    } catch (Throwable t) {
      Log.log("MavenResolver.resolve: dependency manager failure",
              "ids", ids, "coords", coords, t);
    }
    return results;
  }

  private static Coord toCoord (RepoId id) {
    Coord coord = new Coord(id.groupId, id.artifactId, id.version, id.kind);
    coord.classifier = id.classifier;
    return coord;
  }

  private static RepoId toRepoId (Coord coord) {
    return new RepoId(coord.groupId, coord.artifactId, coord.version, coord.kind, coord.classifier);
  }
}

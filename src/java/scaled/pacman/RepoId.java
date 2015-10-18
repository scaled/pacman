//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/** Identifies a Maven dependency. */
public class RepoId implements Depend.Id {

  public static final Path m2repo = Paths.get(Props.userHome).resolve(".m2").resolve("repository");

  // parses a repo depend: groupId:artifactId:version:kind
  public static RepoId parse (String text) {
    String[] bits = text.split(":", 5);
    if (bits.length < 3) throw new IllegalArgumentException(
      "Invalid repo id: "+ text +" (expect 'groupId:artifactId:version')");
    String kind = bits.length > 3 ? bits[3] : "jar";
    return new RepoId(bits[0], bits[1], bits[2], kind);
  }

  // extracts a repo depend from an m2repo path
  public static RepoId fromPath (Path path) {
    if (!path.startsWith(m2repo)) return null;
    Path versDir = path.getParent(), artDir = versDir.getParent();
    Path groupDir = m2repo.relativize(artDir.getParent());
    String file = path.getFileName().toString();
    // TODO: classifier
    return new RepoId(groupDir.toString().replace(File.separatorChar, '.'),
                      artDir.getFileName().toString(), versDir.getFileName().toString(),
                      file.substring(file.lastIndexOf('.')+1));
  }

  public final String groupId;
  public final String artifactId;
  public final String version;
  public final String kind;
  public final String classifier; // null means no classifier

  public RepoId (String groupId, String artifactId, String version, String kind) {
    this(groupId, artifactId, version, kind, null);
  }

  public RepoId (String groupId, String artifactId, String version, String kind, String classifier) {
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.version = version;
    this.kind = kind;
    this.classifier = classifier;
  }

  public String toCoord () { return groupId + ":" + artifactId + ":" + version; }

  @Override public String stableId () { return groupId + ":" + artifactId; }
  @Override public String version () { return version; }

  @Override public String toString () {
    return groupId + ":" + artifactId + ":" + version + ":" + kind +
      (classifier == null ? "" : (":" + classifier));
  }

  @Override public int hashCode () {
    return groupId.hashCode() ^ artifactId.hashCode() ^ version.hashCode() ^ kind.hashCode() ^
      (classifier == null ? 0 : classifier.hashCode());
  }

  @Override public boolean equals (Object other) {
    if (!(other instanceof RepoId)) return false;
    RepoId oid = (RepoId)other;
    return (groupId.equals(oid.groupId) && artifactId.equals(oid.artifactId) &&
            version.equals(oid.version) && kind.equals(oid.kind) &&
            Objects.equals(classifier, oid.classifier));
  }
}

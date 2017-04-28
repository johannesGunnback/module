import java.util.regex.Pattern

class Shell {
    private static final boolean WINDOWS = System.properties['os.name'].toLowerCase().contains('windows')

    ShellResult execute(String command, File workingDir) {
        String output = ''

        Process process = new ProcessBuilder(addShellPrefix(command))
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

        process.inputStream.eachLine { line ->
            output += (line + '\n')
        }

        process.waitFor()

        return new ShellResult(
                command: command,
                workingDir: workingDir,
                output: output.trim(),
                exitValue:process.exitValue()
        )
    }

    private String[] addShellPrefix(String command) {
        if (WINDOWS) {
            return ['cmd', '/C', command]
        }
        return [System.getenv()['SHELL'] ?: 'sh', '-c', command]
    }
}

class ShellResult {
    String command
    File workingDir
    String output
    int exitValue
}

def execute(String command, String dir) {
    ShellResult shellResult = new Shell().execute(command, new File(dir))
    if (shellResult.exitValue != 0) {
        throw new RuntimeException("Can not execute: ${command} (${shellResult.output})")
    }
    return shellResult.output
}

List<String> filter(List<String> branches, String regex) {
    Pattern pattern = Pattern.compile(regex)
    branches.findAll { branch ->
        pattern.matcher(branch).matches()
    }
}

class Project{
    String gitUrl;
    String projectDir;
}

def projects = [new Project(gitUrl:"https://github.com/johannesGunnback/child1.git", projectDir:"child1"),
                new Project(gitUrl:"https://github.com/johannesGunnback/child2.git", projectDir:"child2"),
                new Project(gitUrl:"https://github.com/johannesGunnback/parent.git", projectDir:"parent"),
                new Project(gitUrl:"http://cuso.edb.se/stash/scm/lplan/loanprocess.v1_0.git", projectDir:"loanprocess.v1_0"),
                new Project(gitUrl:"http://cuso.edb.se/stash/scm/lplan/war.loanprocess-dev.git", projectDir:"war.loanprocess-dev"),
                new Project(gitUrl:"http://cuso.edb.se/stash/scm/lplan/dw-batch.v1_0.git", projectDir:"dw-batch.v1_0"),
                new Project(gitUrl:"http://cuso.edb.se/stash/scm/lplan/database-tools.git", projectDir:"database-tools"),
                new Project(gitUrl:"http://cuso.edb.se/stash/scm/lplan/modules.git", projectDir:"modules"),
                new Project(gitUrl:"http://cuso.edb.se/stash/scm/lplan/configuration.war.loanprocess-dev.git", projectDir:"configuration.war.loanprocess-dev")];

def cloneRepos(List<Project> projects) {
    for(Project project : projects){
        try{
            execute("git clone $project.gitUrl", ".");
            println project.projectDir + " cloned"
        }catch(Exception e){
            println e.getMessage();
        }
    }
}

def checkOutBranch(String branch, List<Project> projects){
    for(Project project : projects){
        execute("git fetch -p", project.projectDir);
        execute("git checkout $branch", project.projectDir);
        println "$project.projectDir $branch checked out"
    }

}

def release(List<Project> projects, boolean useReleaseVersion, String branch){
    Project superPom = getProject("pom.super", projects);
    String thePomPath = superPom.projectDir+"/pom.xml";
    String originalVersion = getOriginalVersion(thePomPath);
    String releaseVersion = originalVersion;
    if(useReleaseVersion){
        releaseVersion = getReleaseVersion(originalVersion);
    }else{
        releaseVersion =  getReleaseCandidateReleaseVersion(originalVersion);
    }
    setVersion(releaseVersion, projects, false);
    for(Project project : projects) {
        if(!"modules".equals(project.projectDir)) {
            execute("git commit -am $releaseVersion", project.projectDir)
            execute("git tag -a $releaseVersion -m $releaseVersion", project.projectDir)
            execute("git push origin $releaseVersion", project.projectDir)
            println "New tag $releaseVersion pushed for $project.projectDir"
        }
    }
    checkOutBranch(branch, projects);
    String newSnapshotVersion = getBumpVersion(releaseVersion);
    setVersion(newSnapshotVersion, projects, true);
    for(Project project : projects) {
        if(!"modules".equals(project.projectDir)) {
            execute("git commit -am $newSnapshotVersion", project.projectDir)
            execute("git push", project.projectDir)
            println "New Snapshot version $newSnapshotVersion pushed for $project.projectDir"
        }
    }
    println "Release completed"
}

def setVersion(String version, List<Project> projects, boolean allowSnapshots){
    println "Set new version $version"
    Project superPom = getProject("pom.super", projects);
    Project modules = getProject("modules", projects);
    Project pomParentWar = getProject("pom.parent-war", projects);
    String range = getAllowedVersionRange(version);
    execute("mvn versions:set -DallowSnapshots=$allowSnapshots -DnewVersion=$version", superPom.projectDir);
    execute("mvn clean install", superPom.projectDir);
    execute("mvn versions:commit", superPom.projectDir)
    execute("mvn versions:update-parent -DallowSnapshots=$allowSnapshots -DparentVersion=$range", pomParentWar.projectDir);
    execute("mvn clean install", pomParentWar.projectDir);
    execute("mvn versions:commit", pomParentWar.projectDir)
    execute("mvn -Prelease-script versions:update-parent -DallowSnapshots=$allowSnapshots -DparentVersion=$range", modules.projectDir);
    println "Running a clean install this could take a few minutes..."
    execute("mvn clean install -Prelease-script", modules.projectDir);
    execute("mvn versions:commit -Prelease-script", pomParentWar.projectDir)
}

def getAllowedVersionRange(String version){
    def matcher = (version =~ /([0-9])(\.[0-9]+\.)([0-9]+).*/)
    if(matcher.matches()){
        "[${matcher[0][1]},${Integer.parseInt(matcher[0][1]) +1}]"
    }else{
        throw new RuntimeException("Can not get range of version")
    }
}

def getBumpVersion(String version) {
    def matcher = (version =~ /([0-9]+\.[0-9]+\.[0-9]+)-rc([0-9]+).*/)
    if (matcher.matches()) {
        "${matcher[0][1]}-rc${Integer.parseInt(matcher[0][2]) + 1}-SNAPSHOT"
    } else {
        matcher = (version =~ /([0-9]+\.[0-9]+\.)([0-9]+).*/)
        if (!matcher.matches()) {
            throw new RuntimeException("Can not getBumpVersion")
        }
        "${matcher[0][1]}${Integer.parseInt(matcher[0][2]) + 1}-SNAPSHOT"
    }
}

def getProject(String projectDir, List<Project> projects){
    for(Project project : projects){
        if(project.projectDir.equals(projectDir)){
            return project;
        }
    }
}

def getOriginalVersion(String pomPath){
    def pom = new XmlSlurper().parse(pomPath)
    return pom.version.toString()
}

def getReleaseVersion(String originalVersion) {
    def matcher = (originalVersion =~ /([0-9]+\.[0-9]+\.[0-9]+).*/)
    if (!matcher.matches()) {
        throw new RuntimeException("Can not getReleaseVersion")
    }
    return matcher[0][1]
}

def getReleaseCandidateReleaseVersion(String releaseCandidateVersion) {
    def matcher = (releaseCandidateVersion =~ /([0-9]+\.[0-9]+\.[0-9]+-rc[0-9]+)-SNAPSHOT/)
    if (!matcher.matches()) {
        throw new RuntimeException("Can not getReleaseCandidateReleaseVersion")
    }
    return "${matcher[0][1]}"
}

def process(String branch, boolean clone, boolean fixedRelease, List<Project> projects){
    if(clone){
        cloneRepos(projects);
    }
    checkOutBranch(branch, projects);
    release(projects, fixedRelease, branch)
}

switch (args[0]) {
    case "fix":
        String branch = args[1];
        String clone = args.length > 2 ? args[2] : null;
        process(branch, clone != null && "clone".equals(clone), true, projects)
        break
    case "rc":
        String branch = args[1];
        String clone = args[2];
        process(branch, clone != null && "clone".equals(clone), false, projects)
        break
    default:
        println "Usage: release \"fix/rc\" \"branchname\" \"clone\"(optional)";


}

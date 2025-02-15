package hudson.maven;

import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.FilePath;
import org.junit.Assert;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.ExtractChangeLogSet;

import hudson.Launcher;
import hudson.maven.reporters.MavenArtifactRecord;
import hudson.maven.reporters.MavenFingerprinter;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.Fingerprinter.FingerprintAction;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.util.VirtualFile;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.ExtractResourceWithChangesSCM;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.ToolInstallations;

/**
 * @author Andrew Bayer
 */
public class MavenMultiModuleTest {

    @Rule public JenkinsRule j = new MavenJenkinsRule();

    /**
     * NPE in {@code build.getProject().getWorkspace()} for {@link MavenBuild}.
     */
    @Bug(4192)
    @Test public void multiModMavenWsExists() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.setMaven( Maven36xBuildTest.configureMaven36().getName() );
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod.zip")));
	    assertFalse("MavenModuleSet.isNonRecursive() should be false", m.isNonRecursive());
        j.buildAndAssertSuccess(m);
    }

    @Test public void incrementalMultiModMaven() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.getReporters().add(new TestReporter());
        m.getReporters().add(new MavenFingerprinter());
    	m.setScm(new ExtractResourceWithChangesSCM(getClass().getResource("maven-multimod.zip"),
    						   getClass().getResource("maven-multimod-changes.zip")));
    
    	j.buildAndAssertSuccess(m);
    
    	// Now run a second build with the changes.
    	m.setIncrementalBuild(true);
        j.buildAndAssertSuccess(m);
    
    	MavenModuleSetBuild pBuild = m.getLastBuild();
    	ExtractChangeLogSet changeSet = (ExtractChangeLogSet) pBuild.getChangeSet();
    
    	assertFalse("ExtractChangeLogSet should not be empty.", changeSet.isEmptySet());

    	for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
    	    String parentModuleName = modBuild.getParent().getModuleName().toString();
    	    if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleA")) {
    	        assertEquals("moduleA should have Result.NOT_BUILT", Result.NOT_BUILT, modBuild.getResult());
    	    }
    	    else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleB")) {
    	        assertEquals("moduleB should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
    	    }
    	    else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleC")) {
    	        assertEquals("moduleC should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
    	    }
    	}	
	
	    long summedModuleDuration = 0;
	    for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
	        summedModuleDuration += modBuild.getDuration();
	    }
	    assertTrue("duration of moduleset build should be greater-equal than sum of the module builds",
	            pBuild.getDuration() >= summedModuleDuration);
	    
	    assertFingerprintWereRecorded(pBuild);
    }

    private void assertFingerprintWereRecorded(MavenModuleSetBuild modulesetBuild) {
        boolean mustHaveFingerprints = false;
        for (MavenBuild moduleBuild : modulesetBuild.getModuleLastBuilds().values()) {
            if (moduleBuild.getResult() != Result.NOT_BUILT && moduleBuild.getResult() != Result.ABORTED) {
                assertFingerprintWereRecorded(moduleBuild);
                mustHaveFingerprints = true;
            }
        }
        
        if (mustHaveFingerprints) {
            FingerprintAction action = modulesetBuild.getAction(FingerprintAction.class);
            Assert.assertNotNull(action);
            Assert.assertFalse(action.getFingerprints().isEmpty());
        }
    }

    private void assertFingerprintWereRecorded(MavenBuild moduleBuild) {
        FingerprintAction action = moduleBuild.getAction(FingerprintAction.class);
        Assert.assertNotNull(action);
        Assert.assertFalse(action.getFingerprints().isEmpty());
        
        MavenArtifactRecord artifactRecord = moduleBuild.getAction(MavenArtifactRecord.class);
        Assert.assertNotNull(artifactRecord);
        String fingerprintName = artifactRecord.mainArtifact.groupId + ":" + artifactRecord.mainArtifact.fileName;
        
        Assert.assertTrue("Expected fingerprint " + fingerprintName + " in module build " + moduleBuild,
              action.getFingerprints().containsKey(fingerprintName));
        
        // we should assert more - i.e. that all dependencies are fingerprinted, too,
        // but it's complicated to find out the dependencies of the build
    }

    @Bug(5357)
    @Test public void incrRelMultiModMaven() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.setRootPOM("parent/pom.xml");
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceWithChangesSCM(getClass().getResource("maven-multimod-rel-base.zip"),
						   getClass().getResource("maven-multimod-changes.zip")));
        
        j.buildAndAssertSuccess(m);
        
        // Now run a second build with the changes.
        m.setIncrementalBuild(true);
        j.buildAndAssertSuccess(m);
        
        MavenModuleSetBuild pBuild = m.getLastBuild();
        ExtractChangeLogSet changeSet = (ExtractChangeLogSet) pBuild.getChangeSet();
        
        assertFalse("ExtractChangeLogSet should not be empty.", changeSet.isEmptySet());

        for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
            String parentModuleName = modBuild.getParent().getModuleName().toString();
            if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleA")) {
                assertEquals("moduleA should have Result.NOT_BUILT", Result.NOT_BUILT, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleB")) {
                assertEquals("moduleB should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleC")) {
                assertEquals("moduleC should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
        }	
	
        long summedModuleDuration = 0;
        for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
            summedModuleDuration += modBuild.getDuration();
        }
        assertTrue("duration of moduleset build should be greater-equal than sum of the module builds",
        pBuild.getDuration() >= summedModuleDuration);
    }


    /**
     * NPE in {@code getChangeSetFor(m)} in {@link MavenModuleSetBuild} when incremental build is
     * enabled and a new module is added.
     */
    @Test public void newModMultiModMaven() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceWithChangesSCM(getClass().getResource("maven-multimod.zip"),
                getClass().getResource("maven-multimod-changes.zip")));

        m.setIncrementalBuild(true);
        j.buildAndAssertSuccess(m);
    }

    /**
     * When "-N' or "--non-recursive" show up in the goals, any child modules should be ignored.
     */
    @Bug(4491)
    @Test public void multiModMavenNonRecursiveParsing() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.setGoals("clean install -N -Dmaven.compiler.target=1.8 -Dmaven.compiler.source=1.8");
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod.zip")));

        j.buildAndAssertSuccess(m);

        MavenModuleSetBuild pBuild = m.getLastBuild();

        for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
            String parentModuleName = modBuild.getParent().getModuleName().toString();
            if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:multimod-top")) {
                assertEquals("moduleA should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleA")) {
                assertEquals("moduleA should have Result.NOT_BUILT", Result.NOT_BUILT, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleB")) {
                assertEquals("moduleB should have Result.NOT_BUILT", Result.NOT_BUILT, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod:moduleC")) {
                assertEquals("moduleC should have Result.NOT_BUILT", Result.NOT_BUILT, modBuild.getResult());
            }
	    
        }	
	
    }

    /**
     * Module failures in build X should lead to those modules being re-run in build X+1, even if
     * incremental build is enabled and nothing changed in those modules.
     */
    @Bug(4152)
    @Test public void incrementalMultiModWithErrorsMaven() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.setIncrementalBuild(true);
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceWithChangesSCM(getClass().getResource("maven-multimod-incr.zip"),
						   getClass().getResource("maven-multimod-changes.zip")));

        j.assertBuildStatus(Result.UNSTABLE, m.scheduleBuild2(0).get());
        MavenModuleSetBuild pBuild = m.getLastBuild();
        
        for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
            String parentModuleName = modBuild.getParent().getModuleName().toString();
            if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleA")) {
                assertEquals("moduleA should have Result.UNSTABLE", Result.UNSTABLE, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleB")) {
                assertEquals("moduleB should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleC")) {
                assertEquals("moduleC should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleD")) {
                assertEquals("moduleD should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
        }   

        // Now run a second build with the changes.
        j.assertBuildStatus(Result.UNSTABLE, m.scheduleBuild2(0).get());

    	pBuild = m.getLastBuild();
    	ExtractChangeLogSet changeSet = (ExtractChangeLogSet) pBuild.getChangeSet();

    	assertFalse("ExtractChangeLogSet should not be empty.", changeSet.isEmptySet());
    	// changelog contains a change for module B
    	assertEquals("Parent build should have Result.UNSTABLE", Result.UNSTABLE, pBuild.getResult());
	
    	for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
    	    String parentModuleName = modBuild.getParent().getModuleName().toString();
    	    // A must be build again, because it was UNSTABLE before
    	    if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleA")) {
    	        assertEquals("moduleA should have Result.UNSTABLE", Result.UNSTABLE, modBuild.getResult());
    	    }
    	    // B must be build, because it has changes
    	    else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleB")) {
    	        assertEquals("moduleB should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
    	    }
    	    // C must be build, because it depends on B
    	    else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleC")) {
    	        assertEquals("moduleC should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
    	    }
    	    // D must not be build
    	    else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleD")) {
    	        assertEquals("moduleD should have Result.NOT_BUILT", Result.NOT_BUILT, modBuild.getResult());
    	    }
    	}
    }
    
    /**
     * If "deploy modules" is checked and aggregator build failed
     * then all modules build this time, have to be build next time, again.
     */
    @Bug(5121)
    @Test public void incrementalRedeployAfterAggregatorError() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.setIncrementalBuild(true);
        m.getReporters().add(new TestReporter());
        m.getPublishers().add(new DummyRedeployPublisher());
        m.setScm(new ExtractResourceWithChangesSCM(getClass().getResource("maven-multimod-incr.zip"),
                           getClass().getResource("maven-multimod-changes.zip")));

        j.assertBuildStatus(Result.UNSTABLE, m.scheduleBuild2(0).get());
        MavenModuleSetBuild pBuild = m.getLastBuild();
        
        for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
            String parentModuleName = modBuild.getParent().getModuleName().toString();
            if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleA")) {
                assertEquals("moduleA should have Result.UNSTABLE", Result.UNSTABLE, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleB")) {
                assertEquals("moduleB should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleC")) {
                assertEquals("moduleC should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleD")) {
                assertEquals("moduleD should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
        }   

        // Now run a second build.
        j.assertBuildStatus(Result.UNSTABLE, m.scheduleBuild2(0).get());

        pBuild = m.getLastBuild();
        ExtractChangeLogSet changeSet = (ExtractChangeLogSet) pBuild.getChangeSet();

        assertFalse("ExtractChangeLogSet should not be empty.", changeSet.isEmptySet());
        // changelog contains a change for module B
        assertEquals("Parent build should have Result.UNSTABLE", Result.UNSTABLE, pBuild.getResult());
    
        for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
            String parentModuleName = modBuild.getParent().getModuleName().toString();
            // A must be build again, because it was UNSTABLE before
            if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleA")) {
                assertEquals("moduleA should have Result.UNSTABLE", Result.UNSTABLE, modBuild.getResult());
            }
            // B must be build, because it has changes
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleB")) {
                assertEquals("moduleB should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            // C must be build, because it depends on B
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleC")) {
                assertEquals("moduleC should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            // D must be build again, because it needs to be deployed now
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleD")) {
                assertEquals("moduleD should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
        }
    }
    
    /**
     * Test failures in a child module should lead to the parent being marked as unstable.
     */
    @Bug(4378)
    @Test public void multiModWithTestFailuresMaven() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod-incr.zip")));

        j.assertBuildStatus(Result.UNSTABLE, m.scheduleBuild2(0).get());

        MavenModuleSetBuild pBuild = m.getLastBuild();

        assertEquals("Parent build should have Result.UNSTABLE", Result.UNSTABLE, pBuild.getResult());
	
        for (MavenBuild modBuild : pBuild.getModuleLastBuilds().values()) {
            String parentModuleName = modBuild.getParent().getModuleName().toString();
            if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleA")) {
                assertEquals("moduleA should have Result.UNSTABLE", Result.UNSTABLE, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleB")) {
                assertEquals("moduleB should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleC")) {
                assertEquals("moduleC should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
            else if (parentModuleName.equals("org.jvnet.hudson.main.test.multimod.incr:moduleD")) {
                assertEquals("moduleD should have Result.SUCCESS", Result.SUCCESS, modBuild.getResult());
            }
        }	
    }
    
    @Bug(8484)
    @Test public void multiModMavenNonRecursive() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet m = j.jenkins.createProject(MavenModuleSet.class, "p");
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod.zip")));
        m.setGoals( "-N validate -Dmaven.compiler.target=1.8 -Dmaven.compiler.source=1.8" );
        assertTrue("MavenModuleSet.isNonRecursive() should be true", m.isNonRecursive());
        j.buildAndAssertSuccess(m);
        assertEquals("not only one module", 1, m.getModules().size());
    }    

    @Bug(17713)
    @Test public void modulesPageLinks() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet ms = j.jenkins.createProject(MavenModuleSet.class, "p");
        ms.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod.zip")));
        j.buildAndAssertSuccess(ms);
        MavenModule m = ms.getModule("org.jvnet.hudson.main.test.multimod:moduleA");
        assertNotNull(m);
        assertEquals(1, m.getLastBuild().getNumber());
        JenkinsRule.WebClient wc = j.createWebClient();
        // https://github.com/HtmlUnit/htmlunit/issues/232
        wc.setJavaScriptEnabled(false);
        HtmlPage modulesPage = wc.getPage(ms, "modules");
        modulesPage.getAnchorByText(m.getDisplayName()).openLinkInNewWindow();
    }

    @Bug(17236)
    @Test public void artifactArchiving() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(new TestAMF());
        ToolInstallations.configureMaven35();
        MavenModuleSet mms = j.jenkins.createProject(MavenModuleSet.class, "p");
        mms.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod.zip")));
        mms.setAssignedNode(j.createOnlineSlave());
        j.buildAndAssertSuccess(mms);
        // We want all the artifacts in a given module to be archived in one operation. But modules are archived separately.
        Map<String,Map<String,FilePath>> expected = new TreeMap<>();
        FilePath ws = mms.getModule("org.jvnet.hudson.main.test.multimod$multimod-top").getBuildByNumber(1).getWorkspace();
        expected.put("org.jvnet.hudson.main.test.multimod:multimod-top",
                     Collections.singletonMap("org.jvnet.hudson.main.test.multimod/multimod-top/1.0-SNAPSHOT/multimod-top-1.0-SNAPSHOT.pom",
                                              ws.child( "pom.xml" )));
        for (String module : new String[] {"moduleA", "moduleB", "moduleC"}) {
            Map<String,FilePath> m = new TreeMap<>();
            ws = mms.getModule("org.jvnet.hudson.main.test.multimod$" + module).getBuildByNumber(1).getWorkspace();
            m.put("org.jvnet.hudson.main.test.multimod/" + module + "/1.0-SNAPSHOT/" + module + "-1.0-SNAPSHOT.pom", ws.child("pom.xml"));
            m.put("org.jvnet.hudson.main.test.multimod/" + module + "/1.0-SNAPSHOT/" + module + "-1.0-SNAPSHOT.jar", ws.child("target/" + module + "-1.0-SNAPSHOT.jar"));
            expected.put("org.jvnet.hudson.main.test.multimod:" + module, m);
        }
        System.out.println( "TestAM.archivings: " + TestAM.archivings );
        System.out.println( "expected: " + expected );
        assertEquals(expected.toString(), TestAM.archivings.toString()); // easy to read
        assertEquals(expected, TestAM.archivings); // compares also FileChannel
        // Also check single-module build.
        expected.clear();
        TestAM.archivings.clear();
        MavenBuild isolated = j.buildAndAssertSuccess(mms.getModule("org.jvnet.hudson.main.test.multimod$moduleA"));
        assertEquals(2, isolated.number);
        Map<String,FilePath> m = new TreeMap<>();
        ws = isolated.getWorkspace();
        m.put("org.jvnet.hudson.main.test.multimod/moduleA/1.0-SNAPSHOT/moduleA-1.0-SNAPSHOT.pom", ws.child("pom.xml"));
        m.put("org.jvnet.hudson.main.test.multimod/moduleA/1.0-SNAPSHOT/moduleA-1.0-SNAPSHOT.jar", ws.child("target/moduleA-1.0-SNAPSHOT.jar"));
        expected.put("org.jvnet.hudson.main.test.multimod:moduleA", m);
        assertEquals(expected, TestAM.archivings);
    }

    @Issue("JENKINS-24832")
    @Test
    public void testMultiModulesFailureWithParallelThreads() throws Exception {
        Maven36xBuildTest.configureMaven36();
        MavenModuleSet project = j.createProject(MavenModuleSet.class, "mp");
        project.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod-failure.zip")));
        // Without the parallel option it is correctly failing
        project.setGoals("test -Dmaven.compiler.target=1.8 -Dmaven.compiler.source=1.8");
        j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0).get());
        // With the parallel option it was previously reported as aborted
        project.setGoals("test -T 2 -Dmaven.compiler.target=1.8 -Dmaven.compiler.source=1.8");
        j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0).get());
    }


    public static final class TestAMF extends ArtifactManagerFactory {
        @Override public ArtifactManager managerFor(Run<?,?> build) {
            return new TestAM(build);
        }
    }
    public static final class TestAM extends ArtifactManager {
        static final Map</* module name */String,Map</* archive path */String,/* file in workspace */FilePath>> archivings = new TreeMap<>();
        transient Run<?,?> build;
        TestAM(Run<?,?> build) {
            onLoad(build);
        }
        @Override public void onLoad(Run<?, ?> build) {
            this.build = build;
        }
        @Override public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String,String> artifacts) throws IOException, InterruptedException {
            String name = build.getParent().getName();
            if (archivings.containsKey(name)) {
                // Would be legitimate only if some archived files for a given module were outside workspace, such as repository parent POM, *and* others were inside, which is not the case in this test.
                throw new IOException("repeated archiving to " + name);
            }
            Map<String,FilePath> m = new TreeMap<>();
            for (Map.Entry<String,String> e : artifacts.entrySet()) {
                FilePath f = workspace.child(e.getValue());
                if (f.exists()) {
                    if (f.getRemote().replace('\\', '/').contains("/org/jvnet/hudson/main/test/")) {
                        // Inside the local repository. Hard to know exactly what that path might be, so just mask it out.
                        f = new FilePath(f.getChannel(), f.getRemote().replaceFirst("^.+(?=[/\\\\]org[/\\\\]jvnet[/\\\\]hudson[/\\\\]main[/\\\\]test[/\\\\])", "…").replace('\\', '/'));
                    }
                    System.out.println( "f:" +f );
                    m.put(e.getKey(), f);
                } else {
                    throw new IOException("no such file " + f);
                }
            }
            archivings.put(name, m);
        }
        @Override public boolean delete() throws IOException, InterruptedException {
            throw new IOException();
        }
        @Override public VirtualFile root() {
            throw new UnsupportedOperationException();
        }
    }

    /*
    @Test public void parallelMultiModMavenWsExists() throws Exception {
        ToolInstallations.configureMaven36();
        MavenModuleSet m = jenkins.createProject(MavenModuleSet.class, "p");
	m.setAggregatorStyleBuild(false);
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod.zip")));
        assertBuildStatusSuccess(m.scheduleBuild2(0).get());

	for (MavenModule mod : m.sortedActiveModules) {
	    while (mod.getLastBuild() == null) {
		Thread.sleep(500);
	    }

	    while (mod.getLastBuild().isBuilding()) {
		Thread.sleep(500);
	    }

	    assertBuildStatusSuccess(mod.getLastBuild());
	}
	

	
    }
    
    @Test public void privateRepoParallelMultiModMavenWsExists() throws Exception {
        ToolInstallations.configureMaven36();
        MavenModuleSet m = jenkins.createProject(MavenModuleSet.class, "p");
	m.setAggregatorStyleBuild(false);
	m.setUsePrivateRepository(true);
        m.getReporters().add(new TestReporter());
        m.setScm(new ExtractResourceSCM(getClass().getResource("maven-multimod.zip")));
        assertBuildStatusSuccess(m.scheduleBuild2(0).get());

	for (MavenModule mod : m.sortedActiveModules) {
	    while (mod.getLastBuild() == null) {
		Thread.sleep(500);
	    }
	    
	    while (mod.getLastBuild().isBuilding()) {
		Thread.sleep(500);
	    }

	    assertBuildStatusSuccess(mod.getLastBuild());
	}

    }
    */
    private static class TestReporter extends MavenReporter {
        @Override
        public boolean end(MavenBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            assertNotNull(build.getProject().getWorkspace());
            assertNotNull(build.getWorkspace());
            return true;
        }
    }
    
    private static class DummyRedeployPublisher extends RedeployPublisher {
        public DummyRedeployPublisher() {
            super("", "", false, false);
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                BuildListener listener) throws InterruptedException,
                IOException {
            return true;
        }
    }
}

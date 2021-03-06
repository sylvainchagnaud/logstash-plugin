package jenkins.plugins.logstash;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.Project;
import hudson.tasks.test.AbstractTestResultAction;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;

import jenkins.plugins.logstash.persistence.BuildData;
import jenkins.plugins.logstash.persistence.LogstashIndexerDao;
import jenkins.plugins.logstash.persistence.LogstashIndexerDao.IndexerType;
import net.sf.json.JSONObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

@SuppressWarnings("rawtypes")
@RunWith(MockitoJUnitRunner.class)
public class LogstashNotifierTest {
  // Extension of the unit under test that avoids making calls to Jenkins.getInstance() to get the DAO singleton
  static class MockLogstashNotifier extends LogstashNotifier {
    LogstashIndexerDao dao;

    MockLogstashNotifier(int maxLines, boolean failBuild, LogstashIndexerDao dao) {
      super(maxLines, failBuild);
      this.dao = dao;
    }

    @Override
    LogstashIndexerDao getDao() throws InstantiationException {
      if (dao == null) {
        throw new InstantiationException("DoaTestInstantiationException");
      }

      return dao;
    }

    @Override
    String getJenkinsUrl() {
      return "http://my-jenkins-url";
    }
  }

  @Mock AbstractBuild mockBuild;
  @Mock AbstractTestResultAction mockTestResultAction;
  @Mock Project mockProject;
  @Mock Launcher mockLauncher;
  @Mock BuildListener mockListener;
  @Mock PrintStream mockLogger;
  @Mock LogstashIndexerDao mockDao;

  LogstashNotifier notifier;

  @Before
  public void before() throws Exception {
    when(mockBuild.getResult()).thenReturn(Result.SUCCESS);
    when(mockBuild.getDisplayName()).thenReturn("LogstashNotifierTest");
    when(mockBuild.getProject()).thenReturn(mockProject);
    when(mockBuild.getBuiltOn()).thenReturn(null);
    when(mockBuild.getNumber()).thenReturn(123456);
    when(mockBuild.getDuration()).thenReturn(0L);
    when(mockBuild.getTimestamp()).thenReturn(new GregorianCalendar());
    when(mockBuild.getRootBuild()).thenReturn(mockBuild);
    when(mockBuild.getBuildVariables()).thenReturn(Collections.emptyMap());
    when(mockBuild.getLog(3)).thenReturn(Arrays.asList("line 1", "line 2", "line 3"));
    when(mockBuild.getAction(AbstractTestResultAction.class)).thenReturn(mockTestResultAction);

    when(mockTestResultAction.getTotalCount()).thenReturn(0);
    when(mockTestResultAction.getSkipCount()).thenReturn(0);
    when(mockTestResultAction.getFailCount()).thenReturn(0);
    when(mockTestResultAction.getFailedTests()).thenReturn(Collections.emptyList());

    when(mockProject.getName()).thenReturn("LogstashNotifierTest");

    when(mockListener.getLogger()).thenReturn(mockLogger);

    when(mockDao.buildPayload(Matchers.any(BuildData.class), Matchers.anyString(), Matchers.anyListOf(String.class))).thenReturn(new JSONObject());
    // Initialize mocks
    Mockito.doNothing().when(mockDao).push("{}");

    //when(mockDao.push("{}");
    when(mockDao.getIndexerType()).thenReturn(IndexerType.REDIS);
    when(mockDao.getDescription()).thenReturn("localhost:8080");

    notifier = new MockLogstashNotifier(3, false, mockDao);
  }

  @After
  public void after() throws Exception {
    verifyNoMoreInteractions(mockBuild);
    verifyNoMoreInteractions(mockTestResultAction);
    verifyNoMoreInteractions(mockProject);
    verifyNoMoreInteractions(mockLauncher);
    verifyNoMoreInteractions(mockListener);
    verifyNoMoreInteractions(mockLogger);
    verifyNoMoreInteractions(mockDao);
  }

  @Test
  public void performSuccess() throws Exception {
    // Unit under test
    boolean result = notifier.perform(mockBuild, mockLauncher, mockListener);

    // Verify results
    assertTrue("Build should not be marked as failure", result);

    verify(mockBuild).getId();
    verify(mockBuild, times(2)).getResult();
    verify(mockBuild, times(2)).getParent();
    verify(mockBuild, times(2)).getDisplayName();
    verify(mockBuild).getFullDisplayName();
    verify(mockBuild).getDescription();
    verify(mockBuild).getUrl();
    verify(mockBuild).getAction(AbstractTestResultAction.class);
    verify(mockBuild).getBuiltOn();
    verify(mockBuild, times(2)).getNumber();
    verify(mockBuild).getTimestamp();
    verify(mockBuild, times(3)).getRootBuild();
    verify(mockBuild).getBuildVariables();
    verify(mockBuild).getLog(3);

    verify(mockTestResultAction).getTotalCount();
    verify(mockTestResultAction).getSkipCount();
    verify(mockTestResultAction).getFailCount();
    verify(mockTestResultAction, times(2)).getFailedTests();

    verify(mockProject, times(2)).getName();

    verify(mockDao).buildPayload(Matchers.any(BuildData.class), Matchers.eq("http://my-jenkins-url"), Matchers.anyListOf(String.class));
    verify(mockDao).push("{}");
  }

  @Test
  public void performFailNullDaoDoNotFailBuild() throws Exception {
    // Initialize mocks
    notifier = new MockLogstashNotifier(3, false, null);

    // Unit under test
    boolean result = notifier.perform(mockBuild, mockLauncher, mockListener);

    // Verify results
    assertTrue("Build should not be marked as failure", result);

    verify(mockListener, times(2)).getLogger();

    verify(mockLogger).println(contains("DoaTestInstantiationException"));
    verify(mockLogger).println("[logstash-plugin]: Unable to instantiate LogstashIndexerDao with current configuration.");
  }

  @Test
  public void performFailNullDaoDoFailBuild() throws Exception {
    // Initialize mocks
    notifier = new MockLogstashNotifier(3, true, null);

    // Unit under test
    boolean result = notifier.perform(mockBuild, mockLauncher, mockListener);

    // Verify results
    assertFalse("Build should have been marked as failure", result);

    verify(mockListener, times(2)).getLogger();

    verify(mockLogger).println(contains("DoaTestInstantiationException"));
    verify(mockLogger).println("[logstash-plugin]: Unable to instantiate LogstashIndexerDao with current configuration.");
  }

  @Test
  public void performSuccessNoLogData() throws Exception {
    // Initialize mocks
    when(mockBuild.getLog(Matchers.anyInt())).thenThrow(new IOException("Unable to read log file"));

    // Unit under test
    boolean result = notifier.perform(mockBuild, mockLauncher, mockListener);

    // Verify results
    assertTrue("Build should not be marked as failure", result);

    verify(mockBuild).getId();
    verify(mockBuild, times(2)).getResult();
    verify(mockBuild, times(2)).getParent();
    verify(mockBuild, times(2)).getDisplayName();
    verify(mockBuild).getFullDisplayName();
    verify(mockBuild).getDescription();
    verify(mockBuild).getUrl();
    verify(mockBuild).getAction(AbstractTestResultAction.class);
    verify(mockBuild).getBuiltOn();
    verify(mockBuild, times(2)).getNumber();
    verify(mockBuild).getTimestamp();
    verify(mockBuild, times(3)).getRootBuild();
    verify(mockBuild).getBuildVariables();
    verify(mockBuild).getLog(3);

    verify(mockTestResultAction).getTotalCount();
    verify(mockTestResultAction).getSkipCount();
    verify(mockTestResultAction).getFailCount();
    verify(mockTestResultAction, times(2)).getFailedTests();

    verify(mockProject, times(2)).getName();

    verify(mockListener, times(2)).getLogger();

    verify(mockLogger).println("[logstash-plugin]: Unable to serialize log data.");
    verify(mockLogger).println(Matchers.startsWith("java.io.IOException: Unable to read log file"));

    verify(mockDao).buildPayload(Matchers.any(BuildData.class), Matchers.eq("http://my-jenkins-url"), Matchers.anyListOf(String.class));
    verify(mockDao).push("{}");
  }

  @Test
  public void performSuccessNoTestResults() throws Exception {
    // Initialize mocks
    when(mockBuild.getAction(AbstractTestResultAction.class)).thenReturn(null);

    // Unit under test
    boolean result = notifier.perform(mockBuild, mockLauncher, mockListener);

    // Verify results
    assertTrue("Build should not be marked as failure", result);

    verify(mockBuild).getId();
    verify(mockBuild, times(2)).getResult();
    verify(mockBuild, times(2)).getParent();
    verify(mockBuild, times(2)).getDisplayName();
    verify(mockBuild).getFullDisplayName();
    verify(mockBuild).getDescription();
    verify(mockBuild).getUrl();
    verify(mockBuild).getAction(AbstractTestResultAction.class);
    verify(mockBuild).getBuiltOn();
    verify(mockBuild, times(2)).getNumber();
    verify(mockBuild).getTimestamp();
    verify(mockBuild, times(3)).getRootBuild();
    verify(mockBuild).getBuildVariables();
    verify(mockBuild).getLog(3);

    verify(mockProject, times(2)).getName();

    verify(mockDao).buildPayload(Matchers.any(BuildData.class), Matchers.eq("http://my-jenkins-url"), Matchers.anyListOf(String.class));
    verify(mockDao).push("{}");
  }

  @Test
  public void performFailUnableToPushDoNotFailBuild() throws Exception {
    // Initialize mocks
    Mockito.doThrow(new IOException()).when(mockDao).push("{}");

    // Unit under test
    boolean result = notifier.perform(mockBuild, mockLauncher, mockListener);

    // Verify results
    assertTrue("Build should not be marked as failure", result);

    verify(mockBuild).getId();
    verify(mockBuild, times(2)).getResult();
    verify(mockBuild, times(2)).getParent();
    verify(mockBuild, times(2)).getDisplayName();
    verify(mockBuild).getFullDisplayName();
    verify(mockBuild).getDescription();
    verify(mockBuild).getUrl();
    verify(mockBuild).getAction(AbstractTestResultAction.class);
    verify(mockBuild).getBuiltOn();
    verify(mockBuild, times(2)).getNumber();
    verify(mockBuild).getTimestamp();
    verify(mockBuild, times(3)).getRootBuild();
    verify(mockBuild).getBuildVariables();
    verify(mockBuild).getLog(3);

    verify(mockTestResultAction).getTotalCount();
    verify(mockTestResultAction).getSkipCount();
    verify(mockTestResultAction).getFailCount();
    verify(mockTestResultAction, times(2)).getFailedTests();

    verify(mockProject, times(2)).getName();

    verify(mockListener, times(2)).getLogger();

    verify(mockLogger).println(contains("IOException"));
    verify(mockLogger).println("[logstash-plugin]: Failed to send log data to REDIS:localhost:8080.");

    verify(mockDao).buildPayload(Matchers.any(BuildData.class), Matchers.eq("http://my-jenkins-url"), Matchers.anyListOf(String.class));
    verify(mockDao).push("{}");
    verify(mockDao).getIndexerType();
    verify(mockDao).getDescription();
  }

  @Test
  public void performFailUnableToPushDoFailBuild() throws Exception {
    // Initialize mocks
    notifier = new MockLogstashNotifier(3, true, mockDao);
    Mockito.doThrow(new IOException()).when(mockDao).push("{}");

    // Unit under test
    boolean result = notifier.perform(mockBuild, mockLauncher, mockListener);

    // Verify results
    assertFalse("Build should have been marked as failure", result);

    verify(mockBuild).getId();
    verify(mockBuild, times(2)).getResult();
    verify(mockBuild, times(2)).getParent();
    verify(mockBuild, times(2)).getDisplayName();
    verify(mockBuild).getFullDisplayName();
    verify(mockBuild).getDescription();
    verify(mockBuild).getUrl();
    verify(mockBuild).getAction(AbstractTestResultAction.class);
    verify(mockBuild).getBuiltOn();
    verify(mockBuild, times(2)).getNumber();
    verify(mockBuild).getTimestamp();
    verify(mockBuild, times(3)).getRootBuild();
    verify(mockBuild).getBuildVariables();
    verify(mockBuild).getLog(3);

    verify(mockTestResultAction).getTotalCount();
    verify(mockTestResultAction).getSkipCount();
    verify(mockTestResultAction).getFailCount();
    verify(mockTestResultAction, times(2)).getFailedTests();

    verify(mockProject, times(2)).getName();

    verify(mockListener, times(2)).getLogger();

    verify(mockLogger).println(contains("IOException"));
    verify(mockLogger).println("[logstash-plugin]: Failed to send log data to REDIS:localhost:8080.");

    verify(mockDao).buildPayload(Matchers.any(BuildData.class), Matchers.eq("http://my-jenkins-url"), Matchers.anyListOf(String.class));
    verify(mockDao).push("{}");
    verify(mockDao).getIndexerType();
    verify(mockDao).getDescription();
  }
}

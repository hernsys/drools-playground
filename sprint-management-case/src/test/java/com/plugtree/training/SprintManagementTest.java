package com.plugtree.training;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.drools.core.WorkingMemory;
import org.drools.core.command.impl.CommandBasedStatefulKnowledgeSession;
import org.drools.core.common.AbstractWorkingMemory;
import org.drools.core.event.DefaultAgendaEventListener;
import org.drools.core.event.RuleFlowGroupActivatedEvent;
import org.drools.core.impl.EnvironmentFactory;
import org.drools.core.impl.StatefulKnowledgeSessionImpl;
import org.drools.persistence.SingleSessionCommandService;
import org.jbpm.process.audit.AuditLogService;
import org.jbpm.process.audit.JPAAuditLogService;
import org.jbpm.process.audit.JPAWorkingMemoryDbLogger;
import org.jbpm.process.audit.ProcessInstanceLog;
import org.jbpm.services.task.HumanTaskServiceFactory;
import org.jbpm.services.task.identity.JBossUserGroupCallbackImpl;
import org.jbpm.services.task.utils.ContentMarshallerHelper;
import org.jbpm.services.task.wih.NonManagedLocalHTWorkItemHandler;
import org.jbpm.shared.services.impl.JbpmJTATransactionManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.EnvironmentName;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.api.runtime.process.WorkItemHandler;
import org.kie.api.task.TaskService;
import org.kie.api.task.model.Content;
import org.kie.api.task.model.Status;
import org.kie.api.task.model.Task;
import org.kie.api.task.model.TaskSummary;
import org.kie.internal.task.api.UserGroupCallback;

import bitronix.tm.TransactionManagerServices;
import bitronix.tm.resource.jdbc.PoolingDataSource;

import com.plugtree.training.handlers.CompilationWorkItemHandler;
import com.plugtree.training.handlers.DeploymentWorkItemHandler;
import com.plugtree.training.handlers.NotificationWorkItemHandler;
import com.plugtree.training.model.Requirement;

public class SprintManagementTest {

	private PoolingDataSource ds;
    private ReqsCompletedListener listener;
    private EntityManagerFactory executorEmf;

	@Before
    public void setUp() throws Exception {
		this.ds = new PoolingDataSource();
		this.ds.setUniqueName("jdbc/testDS");
		this.ds.setClassName("org.h2.jdbcx.JdbcDataSource");
		this.ds.setMaxPoolSize(30);
		this.ds.setAllowLocalTransactions(true);
		this.ds.getDriverProperties().setProperty("URL", "jdbc:h2:tasks;MVCC=true;DB_CLOSE_ON_EXIT=TRUE");
		this.ds.getDriverProperties().setProperty("user", "sa");
		this.ds.getDriverProperties().setProperty("password", "sasa");
		this.ds.init();
        this.listener = new ReqsCompletedListener();
    }
	
	@After
	public void tearDown() throws Exception {
		if (this.executorEmf != null) {
			this.executorEmf.close();
		}
		if (this.ds != null) {
			this.ds.close();
		}
	}
	
	private void initRuntime(Map<String, WorkItemHandler> handlers, KieSession session) {
		for (Map.Entry<String, WorkItemHandler> entry : handlers.entrySet()) {
			session.getWorkItemManager().registerWorkItemHandler(entry.getKey(), entry.getValue());
		}
        session.addEventListener(this.listener);
        CommandBasedStatefulKnowledgeSession cmdKsession = (CommandBasedStatefulKnowledgeSession) session;
        SingleSessionCommandService sscs = (SingleSessionCommandService) cmdKsession.getCommandService();
        StatefulKnowledgeSessionImpl realSession = (StatefulKnowledgeSessionImpl) sscs.getKieSession();
        AbstractWorkingMemory wm = (AbstractWorkingMemory) realSession.session;
        wm.addEventListener(new DefaultAgendaEventListener() {
			@Override
			public void afterRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event, WorkingMemory workingMemory) {
				workingMemory.fireAllRules();
			}
		});
        new JPAWorkingMemoryDbLogger(session);
	}

	@Test
	public void testSmallSprint() throws Exception {
		//create task service
		Properties userGroups = new Properties();
		userGroups.put("john", "developers");
		userGroups.put("jean", "developers");
		userGroups.put("charles", "developers");
		userGroups.put("mary", "testers");
		userGroups.put("mark", "testers");
        UserGroupCallback userGroupCallback = new JBossUserGroupCallbackImpl(userGroups);
        TaskService taskService = HumanTaskServiceFactory.newTaskServiceConfigurator()
        	.transactionManager(new JbpmJTATransactionManager())
        	.entityManagerFactory(Persistence.createEntityManagerFactory("org.jbpm.services.task"))
        	.userGroupCallback(userGroupCallback)
        	.getTaskService();

		//create persistent session
		KieServices ks = KieServices.Factory.get();
        KieContainer kc = ks.getKieClasspathContainer();
        KieBase kbase = kc.getKieBase("sprint");
        Environment env = EnvironmentFactory.newEnvironment();
        env.set(EnvironmentName.ENTITY_MANAGER_FACTORY, Persistence.createEntityManagerFactory("org.jbpm.persistence.jpa"));
        env.set(EnvironmentName.TRANSACTION_MANAGER, TransactionManagerServices.getTransactionManager());
        KieSession ksession = ks.getStoreServices().newKieSession(kbase, null, env);
        int sessionId = ksession.getId();
        Map<String, WorkItemHandler> handlers = new HashMap<String, WorkItemHandler>();
        //register work item handlers
        WorkItemHandler htHandler = new NonManagedLocalHTWorkItemHandler(ksession, taskService);
        WorkItemHandler compileHandler = new CompilationWorkItemHandler();
        WorkItemHandler deployHandler = new DeploymentWorkItemHandler();
        WorkItemHandler notificationHandler = new NotificationWorkItemHandler();
        handlers.put("Human Task", htHandler);
        handlers.put("compilation", compileHandler);
        handlers.put("deployment", deployHandler);
        handlers.put("notification", notificationHandler);
        initRuntime(handlers, ksession);
        
        Map<String, Object> params = new HashMap<String, Object>();
        List<Requirement> reqs = new ArrayList<Requirement>();
        reqs.add(new Requirement("Req 1 SIMPLE", "Do something"));
        reqs.add(new Requirement("Req 2 SIMPLE", "Do something else"));
        reqs.add(new Requirement("Req 3 URGENT", "And another thing"));
        params.put("reqs", reqs);
        ProcessInstance instance = ksession.startProcess("sprintManagementProcess", params);
        //process will remain active until a reqsFinished signal is sent or a sprintClosed signal is sent
        Assert.assertNotNull(instance);
        Assert.assertEquals(ProcessInstance.STATE_ACTIVE, instance.getState());
        
        //john is a developer, so he is potential owner of 3 tasks
        List<TaskSummary> johnTasks = taskService.getTasksAssignedAsPotentialOwner("john", "en-UK");
        Assert.assertNotNull(johnTasks);
        Assert.assertEquals(3, johnTasks.size());
        
        //lets make john claims and finishes the first task
        TaskSummary firstTask = johnTasks.iterator().next();
        taskService.claim(firstTask.getId(), "john");
        taskService.start(firstTask.getId(), "john");
        Task johnTask = taskService.getTaskById(firstTask.getId());
        Content content = taskService.getContentById(johnTask.getTaskData().getDocumentContentId());
        Map<?, ?> johnInput = (Map<?, ?>) ContentMarshallerHelper.unmarshall(content.getContent(), env);
        Requirement johnReq = (Requirement) johnInput.get("develReq");
        johnReq.setDeveloperId("john");
        Map<String, Object> johnResults = new HashMap<String, Object>();
        johnResults.put("reqResult", johnReq);
        taskService.complete(johnTask.getId(), "john", johnResults);
        
        //Then, jean and charles take the other two requirements
        List<TaskSummary> jeanTasks = taskService.getTasksAssignedAsPotentialOwner("jean", "en-UK");
        Assert.assertNotNull(jeanTasks);
        Assert.assertEquals(2, jeanTasks.size());
        
        TaskSummary secondTask = jeanTasks.iterator().next();
        taskService.claim(secondTask.getId(), "jean");
        taskService.start(secondTask.getId(), "jean");
        
        List<Status> ready = new ArrayList<Status>();
        ready.add(Status.Ready);
        List<TaskSummary> charlesTasks = taskService.getTasksAssignedAsPotentialOwnerByStatus("charles", ready, "en-UK");
        Assert.assertNotNull(charlesTasks);
        Assert.assertEquals(1, charlesTasks.size());
        
        TaskSummary thirdTask = charlesTasks.iterator().next();
        taskService.claim(thirdTask.getId(), "charles");
        taskService.start(thirdTask.getId(), "charles");
        
        //and they both modify the tasks
        Task jeanTask = taskService.getTaskById(secondTask.getId());
        Content content2 = taskService.getContentById(jeanTask.getTaskData().getDocumentContentId());
        Map<?, ?> jeanInput = (Map<?, ?>) ContentMarshallerHelper.unmarshall(content2.getContent(), env);
        Requirement jeanReq = (Requirement) jeanInput.get("develReq");
        jeanReq.setDeveloperId("jean");
        
        Task charlesTask = taskService.getTaskById(thirdTask.getId());
        Content content3 = taskService.getContentById(charlesTask.getTaskData().getDocumentContentId());
        Map<?, ?> charlesInput = (Map<?, ?>) ContentMarshallerHelper.unmarshall(content3.getContent(), env);
        Requirement charlesReq = (Requirement) charlesInput.get("develReq");
        charlesReq.setDeveloperId("charles");
        
        //and complete them
        Map<String, Object> jeanResults = new HashMap<String, Object>();
        jeanResults.put("reqResult", jeanReq);
        taskService.complete(jeanTask.getId(), "jean", jeanResults);

        Map<String, Object> charlesResults = new HashMap<String, Object>();
        charlesResults.put("reqResult", charlesReq);
        taskService.complete(charlesTask.getId(), "charles", charlesResults);

        //Next day, the server is restarted... session is reloaded
        KieSession ksession2 = ks.getStoreServices().loadKieSession(sessionId, kbase, null, env);
        //Any kiesession dependent work item handlers need to be recreated
        handlers.put("Human Task", new NonManagedLocalHTWorkItemHandler(ksession2, taskService));
        //runtime reassigned
        initRuntime(handlers, ksession2);
        
        //by now compilation and deployment are done for all three tasks, so testers like mary can see them
        List<TaskSummary> maryTasks = taskService.getTasksAssignedAsPotentialOwner("mary", "en-UK");
        Assert.assertNotNull(maryTasks);
        Assert.assertEquals(3, maryTasks.size());

        TaskSummary testTask1 = maryTasks.get(0);
        TaskSummary testTask2 = maryTasks.get(1);
        TaskSummary testTask3 = maryTasks.get(2);
        //mary takes two tasks and mark takes one
        taskService.claim(testTask1.getId(), "mary");
        taskService.claim(testTask2.getId(), "mary");
        taskService.claim(testTask3.getId(), "mark");
        
        //mary finds no bugs in the first task
        taskService.start(testTask1.getId(), "mary");
        Task maryTask1 = taskService.getTaskById(testTask1.getId());
        Content testContent1 = taskService.getContentById(maryTask1.getTaskData().getDocumentContentId());
        Map<?, ?> maryInput1 = (Map<?, ?>) ContentMarshallerHelper.unmarshall(testContent1.getContent(), env);
        Requirement maryReq1 = (Requirement) maryInput1.get("testReq");
        maryReq1.setTesterId("mary");
        maryReq1.setTested(true);
        Map<String, Object> testResults1 = new HashMap<String, Object>();
        testResults1.put("reqResult", maryReq1);
        taskService.complete(maryTask1.getId(), "mary", testResults1);
        
        //mark finds two bugs in the third task
        taskService.start(testTask3.getId(), "mark");
        Task markTask = taskService.getTaskById(testTask3.getId());
        Content testContent3 = taskService.getContentById(markTask.getTaskData().getDocumentContentId());
        Map<?, ?> markInput = (Map<?, ?>) ContentMarshallerHelper.unmarshall(testContent3.getContent(), env);
        Requirement markReq = (Requirement) markInput.get("testReq");
        markReq.addBug("Doesnt look good");
        markReq.addBug("Finds twice the same data");
        markReq.setTested(true);
        Map<String, Object> testResults3 = new HashMap<String, Object>();
        testResults3.put("reqResult", markReq);
        taskService.complete(markTask.getId(), "mark", testResults3);
        
        //mary finds a bug in the second task
        taskService.start(testTask2.getId(), "mary");
        Task maryTask2 = taskService.getTaskById(testTask2.getId());
        Content testContent2 = taskService.getContentById(maryTask2.getTaskData().getDocumentContentId());
        Map<?, ?> maryInput2 = (Map<?, ?>) ContentMarshallerHelper.unmarshall(testContent2.getContent(), env);
        Requirement maryReq2 = (Requirement) maryInput2.get("testReq");
        maryReq2.setTesterId("mary");
        maryReq2.addBug("Wont load");
        maryReq2.setTested(true);
        Map<String, Object> testResults2 = new HashMap<String, Object>();
        testResults2.put("reqResult", maryReq2);
        taskService.complete(maryTask2.getId(), "mary", testResults2);
        
        //After lunch, the server is restarted... session is reloaded
        KieSession ksession3 = ks.getStoreServices().loadKieSession(sessionId, kbase, null, env);
        //Any kiesession dependent work item handlers need to be recreated
        handlers.put("Human Task", new NonManagedLocalHTWorkItemHandler(ksession3, taskService));
        //runtime reassigned
        initRuntime(handlers, ksession3);
        
        //Developers have 2 tasks to bugfix
        List<TaskSummary> bugfixTasks = taskService.getTasksAssignedAsPotentialOwner("jean", "en-UK");
        Assert.assertNotNull(bugfixTasks);
        Assert.assertEquals(2, bugfixTasks.size());
        
        //john is out on leave so jean and charles take one bugfix each
        TaskSummary bugTask1 = bugfixTasks.get(0);
        TaskSummary bugTask2 = bugfixTasks.get(1);
        taskService.claim(bugTask1.getId(), "jean");
        taskService.claim(bugTask2.getId(), "charles");
        
        //first task has two bugs, mary dismisses one and solves another
        taskService.start(bugTask1.getId(), "jean");
        Task jeanBugTask = taskService.getTaskById(bugTask1.getId());
        Content bugContent1 = taskService.getContentById(jeanBugTask.getTaskData().getDocumentContentId());
        Map<?, ?> jeanBugInput = (Map<?, ?>) ContentMarshallerHelper.unmarshall(bugContent1.getContent(), env);
        Requirement jeanBug = (Requirement) jeanBugInput.get("bugfixReq");
        jeanBug.resolveBug("Doesnt look good", "'Dont use IE6' by mary");
        jeanBug.resolveBug("Finds twice the same data", "'Solved by' mary");
        jeanBug.setTested(false);
        Map<String, Object> bugfixResults1 = new HashMap<String, Object>();
        bugfixResults1.put("reqResult", jeanBug);
        taskService.complete(jeanBugTask.getId(), "jean", bugfixResults1);
        
        //seconds task has one bugs, charles solves it
        taskService.start(bugTask2.getId(), "charles");
        Task charlesBugTask = taskService.getTaskById(bugTask2.getId());
        Content bugContent2 = taskService.getContentById(charlesBugTask.getTaskData().getDocumentContentId());
        Map<?, ?> charlesBugInput = (Map<?, ?>) ContentMarshallerHelper.unmarshall(bugContent2.getContent(), env);
        Requirement charlesBug = (Requirement) charlesBugInput.get("bugfixReq");
        charlesBug.resolveBug("Wont load", "'Solved by' charles");
        charlesBug.setTested(false);
        Map<String, Object> bugfixResults2 = new HashMap<String, Object>();
        bugfixResults2.put("reqResult", charlesBug);
        taskService.complete(charlesBugTask.getId(), "charles", bugfixResults2);
        
        //Now we're back at testing the two corrected tasks
        List<TaskSummary> fixedTasks = taskService.getTasksAssignedAsPotentialOwner("mary", "en-UK");
        Assert.assertNotNull(fixedTasks);
        Assert.assertEquals(2, fixedTasks.size());
        
        //mary takes one and mark another
        TaskSummary fixTask1 = fixedTasks.get(0);
        TaskSummary fixTask2 = fixedTasks.get(1);
        taskService.claim(fixTask1.getId(), "mary");
        taskService.claim(fixTask2.getId(), "mark");
        taskService.start(fixTask1.getId(), "mary");
        taskService.start(fixTask2.getId(), "mark");
        
        //They don't find any bugs in either requirement
        Task maryFixTask = taskService.getTaskById(fixTask1.getId());
        Content fixContent1 = taskService.getContentById(maryFixTask.getTaskData().getDocumentContentId());
        Map<?, ?> fixInput1 = (Map<?, ?>) ContentMarshallerHelper.unmarshall(fixContent1.getContent(), env);
        Requirement fixReq1 = (Requirement) fixInput1.get("testReq");
        fixReq1.setTested(true);
        fixReq1.setBugs(new ArrayList<String>());
        
        Task markFixTask = taskService.getTaskById(fixTask2.getId());
        Content fixContent2 = taskService.getContentById(markFixTask.getTaskData().getDocumentContentId());
        Map<?, ?> fixInput2 = (Map<?, ?>) ContentMarshallerHelper.unmarshall(fixContent2.getContent(), env);
        Requirement fixReq2 = (Requirement) fixInput2.get("testReq");
        fixReq2.setTested(true);
        fixReq2.setBugs(new ArrayList<String>());
        
        Map<String, Object> fixTestResults1 = new HashMap<String, Object>();
        fixTestResults1.put("reqResult", fixReq1);
        
        Map<String, Object> fixTestResults2 = new HashMap<String, Object>();
        fixTestResults2.put("reqResult", fixReq2);
        
        //they complete the testing task
        taskService.complete(fixTask1.getId(), "mary", fixTestResults1);
        taskService.complete(fixTask2.getId(), "mark", fixTestResults2);
        
        //After finishing all 3 requirements, sprint is completed, so sprintManagementProcess process should be completed as well
        AuditLogService history = new JPAAuditLogService(env);
        List<ProcessInstanceLog> sprintProcesses =  history.findProcessInstances("sprintManagementProcess");
        Assert.assertNotNull(sprintProcesses);
        Assert.assertEquals(1, sprintProcesses.size());
        ProcessInstanceLog log  = sprintProcesses.iterator().next();
        Assert.assertEquals(ProcessInstance.STATE_COMPLETED, log.getStatus().intValue());
	}
}

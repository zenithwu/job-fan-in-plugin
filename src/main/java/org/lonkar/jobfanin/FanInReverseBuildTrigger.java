package org.lonkar.jobfanin;

import hudson.Extension;
import hudson.Util;
import hudson.console.ModelHyperlinkNote;
import hudson.model.*;
import hudson.model.DependencyGraph.Dependency;
import hudson.model.Queue;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.model.Run;
import hudson.util.RunList;
import jenkins.model.DependencyDeclarer;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.security.QueueItemAuthenticatorConfiguration;

import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Similar to {# ReverseBuildTrigger} it triggers job on downstream, but checks for all the upstream jobs are build and are stable
 * @author lonkar.yogeshr@gmail.com
 * Mar 1, 2016
 *
 */
@SuppressWarnings("rawtypes")
public final class FanInReverseBuildTrigger extends Trigger<Job> implements DependencyDeclarer {

	private static final Logger LOGGER = Logger.getLogger(FanInReverseBuildTrigger.class.getName());
	private static final Map<Job, Collection<FanInReverseBuildTrigger>> upstream2Trigger = new WeakHashMap<>();

	private String upstreamProjects;
	private String upstreamParams;
	private ParametersAction action = null;
//	private final boolean watchUpstreamRecursively;
	private final Result threshold;
//	private transient ArrayList<Job> upsteamProjects;
//	private transient DependencyGraph dependencyGraph;

//	private static final String UPSTREAM_PROJECTS = "upstreamProjects";
	private static final String LOG_TAG = "[job-fan-in]";
//	private static final String TOKEN = "time_hour";
//	public static List<Job> jobs;


	@DataBoundConstructor
	public FanInReverseBuildTrigger(String upstreamProjects, String upstreamParams, Result threshold) {
		this.upstreamProjects = upstreamProjects;
		this.upstreamParams = upstreamParams;
		this.threshold = threshold;
//		this.upsteamProjects = new ArrayList<Job>();
	}

	public String getUpstreamProjects() {
		return upstreamProjects;
	}

	public Result getThreshold() {
		return threshold;
	}

	public String getUpstreamParams() {
		return upstreamParams;
	}

	private boolean shouldTrigger(Run upstreamBuild, TaskListener listener) {
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins == null) {
			return false;
		}
		// This checks Item.READ also on parent folders; note we are checking as
		// the upstream auth currently:
		boolean downstreamVisible = jenkins.getItemByFullName(job.getFullName()) == job;
		Authentication originalAuth = Jenkins.getAuthentication();
		Job upstream = upstreamBuild.getParent();
		Authentication auth = Tasks.getAuthenticationOf((Queue.Task) job);
		if (auth.equals(ACL.SYSTEM) && !QueueItemAuthenticatorConfiguration.get().getAuthenticators().isEmpty()) {
			auth = Jenkins.ANONYMOUS; // cf. BuildTrigger
		}
		SecurityContext orig = ACL.impersonate(auth);
		try {
			if (jenkins.getItemByFullName(upstream.getFullName()) != upstream) {
				if (downstreamVisible) {
					listener.getLogger()
							.println(
									"Running as "
											+ (auth.getName() + " cannot even see " + upstream.getFullName()
													+ " for trigger from " + job.getFullName()));
				} else {
					LOGGER.log(Level.WARNING,
							"Running as {0} cannot even see {1} for trigger from {2} (but cannot tell {3} that)",
							new Object[] { auth.getName(), upstream, job, originalAuth.getName() });
				}
				return false;
			}
			// No need to check Item.BUILD on downstream, because the downstream
			// projectâ€™s configurer has asked for this.
		} finally {
			SecurityContextHolder.setContext(orig);
		}
		Result result = upstreamBuild.getResult();
		return result != null && result.isBetterOrEqualTo(threshold);
	}

	private boolean UpstreamIsBuildByToken(AbstractBuild upstreamBuild, TaskListener listener, ArrayList<Job> upsteamProjects, List<Action> actions)
	{
		ParametersAction downstreamAction = (ParametersAction)upstreamBuild.getAction(ParametersAction.class);

//		if (org.lonkar.jobfanin.bdp.Util.isEmpty(downstreamAction)) {
//			LOGGER.info("current project hasn't parameter and token:time_hour is needed.");
//			listener.getLogger().println("current project hasn't parameter and token:time_hour is needed.");
//			return false;
//		}
//		ParameterValue parameterValue = downstreamAction.getParameter("time_hour");
//
//		if (org.lonkar.jobfanin.bdp.Util.isEmpty(parameterValue)) {
//			LOGGER.info("current parameter token is null.");
//			listener.getLogger().println("current parameter token is null.please create token:time_hour");
//			return false;
//		}

		LOGGER.info("#######current project name:" + upstreamBuild.getFullDisplayName() + "######");
		ArrayList runListTime = new ArrayList();
		for (Job upstream : upsteamProjects) {
			RunList<Run> runList = upstream.getNewBuilds();
			int i = 0;
			for (Run run : runList) {
//				ParametersAction action = run.getAction(ParametersAction.class);

//				LOGGER.info("upstream project name:" + upstream.getName() + " parameter:" + action
//						.getParameter("time_hour") +
//						" result:" + run
//						.getResult());

				if (
						(null != run
								.getResult()) &&
						(run
								.getResult().isBetterOrEqualTo(this.threshold))) {
					Long timeRun = Long.valueOf(run.getStartTimeInMillis() + run.getDuration());
					runListTime.add(timeRun.toString() + run.getFullDisplayName());
					LOGGER.info("runTime:" + timeRun.toString() + run.getFullDisplayName());
					break;
				}
				i++;
			}

			if (i == runList.size()) {
				return false;
			}
		}

		this.action = upstreamBuild.getAction(ParametersAction.class);
		actions.add(this.action);
		Long timeRun = Long.valueOf(upstreamBuild.getStartTimeInMillis() + upstreamBuild.getDuration());
		String thisRunTime = timeRun.toString() + upstreamBuild.getFullDisplayName();
		Collections.sort(runListTime);
		if ((runListTime.size() > 0) && (!runListTime.get(runListTime.size() - 1).equals(thisRunTime))) {
			LOGGER.warning(String.format(" thisRunTime:%s;\n runListTime:%s", new Object[] { thisRunTime, runListTime.toString() }));
			return false;
		}
		return true;
	}



	/**
	 *
	 * @author lonkar.yogeshr@gmail.com
	 * Mar 1, 2016
	 *
	 */
	public static class FanInDependency extends DependencyGraph.Dependency {

		private String description;

		public FanInDependency(AbstractProject upstream, AbstractProject downstream, String description) {
			super(upstream, downstream);
			this.description = description;
		}

		public String getDescription() {
			return description;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;

			final Dependency that = (Dependency) obj;
			return getUpstreamProject() == that.getUpstreamProject()
					|| this.getDownstreamProject() == that.getDownstreamProject();
		}

		@Override
		public int hashCode() {
			int hash = 7;
			hash = 23 * hash + this.getUpstreamProject().hashCode();
			hash = 23 * hash + this.getDownstreamProject().hashCode();
			return hash;
		}

		@Override
		public String toString() {
			return super.toString() + "[" + getUpstreamProject() + "->" + getDownstreamProject() + "]";
		}

	}

	/**
	 * Check if job is currently not building & is stable.
	 * @param job to be checked
	 * @return true of stable and not building
	 */
	private boolean isNotBuildingAndStable(Job job) {
		if (!job.isBuilding() && !job.isInQueue() && job.getLastBuild() != null) {
			Result result = job.getLastBuild().getResult();
			if (result != null && result.isBetterOrEqualTo(threshold)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public synchronized void buildDependencyGraph(final AbstractProject downstream, DependencyGraph graph)
	{
		for (AbstractProject upstream : Items.fromNameList(downstream.getParent(), this.upstreamProjects, AbstractProject.class))
		{
			graph.addDependency(new FanInDependency(upstream, downstream, "")
			{
				public boolean shouldTriggerBuild(AbstractBuild upstreamBuild, TaskListener listener, List<Action> actions) {
					ArrayList upsteamProjects = new ArrayList();

					for (Job upstream : Items.fromNameList(downstream.getParent(), FanInReverseBuildTrigger.this.upstreamProjects, Job.class)) {
						upsteamProjects.add(upstream);
					}

					return (FanInReverseBuildTrigger.this.shouldTrigger(upstreamBuild, listener)) && (FanInReverseBuildTrigger.this.UpstreamIsBuildByToken(upstreamBuild, listener, upsteamProjects, actions));
				}
			});
		}
	}

	@Override
	public void start(Job project, boolean newInstance) {
		super.start(project, newInstance);
		SecurityContext orig = ACL.impersonate(ACL.SYSTEM);
		try {
			for (Job upstream : Items.fromNameList(project.getParent(), upstreamProjects, Job.class)) {
				if (upstream instanceof AbstractProject && project instanceof AbstractProject) {
					continue; // handled specially
				}
				synchronized (upstream2Trigger) {
					Collection<FanInReverseBuildTrigger> triggers = upstream2Trigger.get(upstream);
					if (triggers == null) {
						triggers = new LinkedList<FanInReverseBuildTrigger>();
						upstream2Trigger.put(upstream, triggers);
					}
					triggers.remove(this);
					triggers.add(this);
				}
			}
		} finally {
			SecurityContextHolder.setContext(orig);
		}
	}

	@Override
	public void stop() {
		super.stop();
		synchronized (upstream2Trigger) {
			for (Collection<FanInReverseBuildTrigger> triggers : upstream2Trigger.values()) {
				triggers.remove(this);
			}
		}
		upstream2Trigger.clear();
	}

	@Extension
	public static final class DescriptorImpl extends TriggerDescriptor {

		@Override
		public String getDisplayName() {
			return "BDPFanIn";
		}

		@Override
		public boolean isApplicable(Item item) {
			return item instanceof Job && item instanceof ParameterizedJobMixIn.ParameterizedJob;
		}

		public AutoCompletionCandidates doAutoCompleteUpstreamProjects(@QueryParameter String value,
				@AncestorInPath Item self, @AncestorInPath ItemGroup container) {
			return AutoCompletionCandidates.ofJobNames(Job.class, value, self, container);
		}

		public FormValidation doCheckUpstreamProjects(@AncestorInPath Job project, @QueryParameter String value) {
			if (!project.hasPermission(Item.CONFIGURE)) {
				return FormValidation.ok();
			}
			StringTokenizer tokens = new StringTokenizer(Util.fixNull(value), ",");
			boolean hasProjects = false;
			while (tokens.hasMoreTokens()) {
				String projectName = tokens.nextToken().trim();
				if (StringUtils.isNotBlank(projectName)) {
					Jenkins jenkins = Jenkins.getInstance();
					if (jenkins == null) {
						return FormValidation.ok();
					}
					Job item = jenkins.getItem(projectName, project, Job.class);
					if (item == null) {
						Job nearest = Items.findNearest(Job.class, projectName, project.getParent());
						String alternative = nearest != null ? nearest.getRelativeNameFrom(project) : "?";
						return FormValidation.error(hudson.tasks.Messages.BuildTrigger_NoSuchProject(projectName,
								alternative));
					}
					hasProjects = true;
				}
			}
			if (!hasProjects) {
				return FormValidation.error(hudson.tasks.Messages.BuildTrigger_NoProjectSpecified());
			}

			return FormValidation.ok();
		}

	}

	@Extension
	public static final class RunListenerImpl extends RunListener<Run> {
		@Override
		public void onCompleted(Run r, TaskListener listener) {
			Collection<FanInReverseBuildTrigger> triggers;
			synchronized (upstream2Trigger) {
				Collection<FanInReverseBuildTrigger> _triggers = upstream2Trigger.get(r.getParent());
				if (_triggers == null || _triggers.isEmpty()) {
					return;
				}
				triggers = new ArrayList<FanInReverseBuildTrigger>(_triggers);
			}
			for (final FanInReverseBuildTrigger trigger : triggers) {
				if (trigger.shouldTrigger(r, listener)) {
					if (!trigger.job.isBuildable()) {
						listener.getLogger().println(
								hudson.tasks.Messages.BuildTrigger_Disabled(ModelHyperlinkNote.encodeTo(trigger.job)));
						continue;
					}
					String name = ModelHyperlinkNote.encodeTo(trigger.job) + " #" + trigger.job.getNextBuildNumber();
					if (ParameterizedJobMixIn.scheduleBuild2(trigger.job, -1, new CauseAction(new Cause.UpstreamCause(r))) != null) {
						listener.getLogger().println(hudson.tasks.Messages.BuildTrigger_Triggering(name));
					} else {
						listener.getLogger().println(hudson.tasks.Messages.BuildTrigger_InQueue(name));
					}
				}
			}
		}
	}

	@Extension
	public static class ItemListenerImpl extends ItemListener {
		@Override
		public void onLocationChanged(Item item, String oldFullName, String newFullName) {
			Jenkins jenkins = Jenkins.getInstance();
			if (jenkins == null) {
				return;
			}
			for (Job<?, ?> p : jenkins.getAllItems(Job.class)) {
				FanInReverseBuildTrigger t = ParameterizedJobMixIn.getTrigger(p, FanInReverseBuildTrigger.class);
				if (t != null) {
					String revised = Items.computeRelativeNamesAfterRenaming(oldFullName, newFullName, t.upstreamProjects,
							p.getParent());
					if (!revised.equals(t.upstreamProjects)) {
						t.upstreamProjects = revised;
						try {
							p.save();
						} catch (IOException e) {
							LOGGER.log(Level.WARNING, "Failed to persist project setting during rename from " + oldFullName
									+ " to " + newFullName, e);
						}
					}
				}
			}
		}
	}
}

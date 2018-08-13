package org.lonkar.jobfanin;

import org.lonkar.jobfanin.bdp.Util;
import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.AutoCompletionCandidates;
import hudson.model.CauseAction;
import hudson.model.DependencyGraph;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Cause.UpstreamCause;
import hudson.model.DependencyGraph.Dependency;
import hudson.model.Queue.Task;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.tasks.Messages;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import hudson.util.RunList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.DependencyDeclarer;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.ParameterizedJobMixIn.ParameterizedJob;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public final class FanInReverseBuildTrigger extends Trigger<Job> implements DependencyDeclarer {
	private static final Logger LOGGER = Logger.getLogger(FanInReverseBuildTrigger.class.getName());
	protected static Map<Job, Collection<FanInReverseBuildTrigger>> upstream2Trigger = new WeakHashMap();
	private ParametersAction action = null;
	private static final String UPSTREAM_PROJECTS = "upstreamProjects";
	private static final String LOG_TAG = "[job-fan-in]";
	private static final String TOKEN = "time_hour";
	public String upstreamProjects;
	public String upstreamParams;
	private final Result threshold;
	public static List<Job> jobs;

	@DataBoundConstructor
	public FanInReverseBuildTrigger(String upstreamProjects, String upstreamParams, Result threshold) {
		this.upstreamParams = upstreamParams;
		this.upstreamProjects = upstreamProjects;
		this.threshold = threshold;
	}

	public String getUpstreamProjects() {
		return this.upstreamProjects;
	}

	public String getUpstreamParams() {
		return this.upstreamParams;
	}

	private boolean shouldTrigger(Run upstreamBuild, TaskListener listener) {
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins == null) {
			return false;
		} else {
			boolean downstreamVisible = jenkins.getItemByFullName(((Job)this.job).getFullName()) == this.job;
			Authentication originalAuth = Jenkins.getAuthentication();
			Job upstream = upstreamBuild.getParent();
			Authentication auth = Tasks.getAuthenticationOf((Task)this.job);
			if (auth.equals(ACL.SYSTEM) && !QueueItemAuthenticatorConfiguration.get().getAuthenticators().isEmpty()) {
				auth = Jenkins.ANONYMOUS;
			}

			SecurityContext orig = ACL.impersonate(auth);

			label91: {
				boolean var9;
				try {
					if (jenkins.getItemByFullName(upstream.getFullName()) == upstream) {
						break label91;
					}

					if (downstreamVisible) {
						listener.getLogger().println("Running as " + auth.getName() + " cannot even see " + upstream.getFullName() + " for trigger from " + ((Job)this.job).getFullName());
					} else {
						LOGGER.log(Level.WARNING, "Running as {0} cannot even see {1} for trigger from {2} (but cannot tell {3} that)", new Object[]{auth.getName(), upstream, this.job, originalAuth.getName()});
					}

					var9 = false;
				} finally {
					SecurityContextHolder.setContext(orig);
				}

				return var9;
			}

			Result result = upstreamBuild.getResult();
			return result != null && result.isBetterOrEqualTo(this.threshold);
		}
	}

	private boolean UpstreamIsBuildByToken(AbstractBuild upstreamBuild, TaskListener listener, ArrayList<Job> upsteamProjects, List<Action> actions) {
		ParametersAction downstreamAction = (ParametersAction)upstreamBuild.getAction(ParametersAction.class);
		if (Util.isEmpty(downstreamAction)) {
			LOGGER.info("current project hasn't parameter and token:time_hour is needed.");
			listener.getLogger().println("current project hasn't parameter and token:time_hour is needed.");
			return false;
		} else {
			ParameterValue parameterValue = downstreamAction.getParameter("time_hour");
			if (Util.isEmpty(parameterValue)) {
				LOGGER.info("current parameter token is null.");
				listener.getLogger().println("current parameter token is null.please create token:time_hour");
				return false;
			} else {
				LOGGER.info("#######current project name:" + upstreamBuild.getFullDisplayName() + "######");
				ArrayList<String> runListTime = new ArrayList();
				Iterator var8 = upsteamProjects.iterator();

				RunList runList;
				int i;
				do {
					if (!var8.hasNext()) {
						this.action = (ParametersAction)upstreamBuild.getAction(ParametersAction.class);
						actions.add(this.action);
						Long timeRun = upstreamBuild.getStartTimeInMillis() + upstreamBuild.getDuration();
						String thisRunTime = timeRun.toString() + upstreamBuild.getFullDisplayName();
						Collections.sort(runListTime);
						if (runListTime.size() > 0 && !((String)runListTime.get(runListTime.size() - 1)).equals(thisRunTime)) {
							LOGGER.warning(String.format(" thisRunTime:%s;\n runListTime:%s", thisRunTime, runListTime.toString()));
							return false;
						}

						return true;
					}

					Job upstream = (Job)var8.next();
					runList = upstream.getNewBuilds();
					i = 0;

					for(Iterator var12 = runList.iterator(); var12.hasNext(); ++i) {
						Run run = (Run)var12.next();
						ParametersAction action = (ParametersAction)run.getAction(ParametersAction.class);
						LOGGER.info("upstream project name:" + upstream.getName() + " parameter:" + action.getParameter("time_hour") + " result:" + run.getResult());
						if (parameterValue.equals(action.getParameter("time_hour")) && null != run.getResult() && run.getResult().isBetterOrEqualTo(this.threshold)) {
							Long timeRun = run.getStartTimeInMillis() + run.getDuration();
							runListTime.add(timeRun.toString() + run.getFullDisplayName());
							LOGGER.info("runTime:" + timeRun.toString() + run.getFullDisplayName());
							break;
						}
					}
				} while(i != runList.size());

				return false;
			}
		}
	}

	private boolean isNotBuildingAndStable(Job job) {
		if (!job.isBuilding()) {
			Result result = job.getLastBuild().getResult();
			if (result != null && result.isBetterOrEqualTo(this.threshold)) {
				return true;
			}
		}

		return false;
	}

	public synchronized void buildDependencyGraph(final AbstractProject downstream, DependencyGraph graph) {
		Iterator var3 = Items.fromNameList(downstream.getParent(), this.upstreamProjects, AbstractProject.class).iterator();

		while(var3.hasNext()) {
			AbstractProject upstream = (AbstractProject)var3.next();
			graph.addDependency(new FanInReverseBuildTrigger.FanInDependency(upstream, downstream, "") {
				public boolean shouldTriggerBuild(AbstractBuild upstreamBuild, TaskListener listener, List<Action> actions) {
					ArrayList<Job> upsteamProjects = new ArrayList();
					Iterator var5 = Items.fromNameList(downstream.getParent(), FanInReverseBuildTrigger.this.upstreamProjects, Job.class).iterator();

					while(var5.hasNext()) {
						Job upstream = (Job)var5.next();
						upsteamProjects.add(upstream);
					}

					return FanInReverseBuildTrigger.this.shouldTrigger(upstreamBuild, listener) && FanInReverseBuildTrigger.this.UpstreamIsBuildByToken(upstreamBuild, listener, upsteamProjects, actions);
				}
			});
		}

	}

	public void start(Job project, boolean newInstance) {
		super.start(project, newInstance);
		SecurityContext orig = ACL.impersonate(ACL.SYSTEM);

		try {
			Iterator var4 = Items.fromNameList(project.getParent(), this.upstreamProjects, Job.class).iterator();

			while(var4.hasNext()) {
				Job upstream = (Job)var4.next();
				if (!(upstream instanceof AbstractProject) || !(project instanceof AbstractProject)) {
					Map var6 = upstream2Trigger;
					synchronized(upstream2Trigger) {
						Collection<FanInReverseBuildTrigger> triggers = (Collection)upstream2Trigger.get(upstream);
						if (triggers == null) {
							triggers = new LinkedList();
							upstream2Trigger.put(upstream, triggers);
						}

						((Collection)triggers).remove(this);
						((Collection)triggers).add(this);
					}
				}
			}
		} finally {
			SecurityContextHolder.setContext(orig);
		}

	}

	public void stop() {
		super.stop();
		Map var1 = upstream2Trigger;
		synchronized(upstream2Trigger) {
			Iterator var2 = upstream2Trigger.values().iterator();

			while(true) {
				if (!var2.hasNext()) {
					break;
				}

				Collection<FanInReverseBuildTrigger> triggers = (Collection)var2.next();
				triggers.remove(this);
			}
		}

		upstream2Trigger.clear();
	}

	@Extension
	public static class ItemListenerImpl extends ItemListener {
		public ItemListenerImpl() {
		}

		public void onLocationChanged(Item item, String oldFullName, String newFullName) {
			Jenkins jenkins = Jenkins.getInstance();
			if (jenkins != null) {
				DependencyGraph dependencyGraph = jenkins.getDependencyGraph();
				Iterator var6 = jenkins.getAllItems(Job.class).iterator();

				while(var6.hasNext()) {
					Job<?, ?> p = (Job)var6.next();
					FanInReverseBuildTrigger t = (FanInReverseBuildTrigger)ParameterizedJobMixIn.getTrigger(p, FanInReverseBuildTrigger.class);
					if (t != null) {
						FanInReverseBuildTrigger.upstream2Trigger.clear();
						String revised = Items.computeRelativeNamesAfterRenaming(oldFullName, newFullName, t.upstreamProjects, p.getParent());
						if (!revised.equals(t.upstreamProjects)) {
							t.upstreamProjects = revised;

							try {
								p.save();
							} catch (IOException var11) {
								FanInReverseBuildTrigger.LOGGER.log(Level.WARNING, "Failed to persist project setting during rename from " + oldFullName + " to " + newFullName, var11);
							}
						}
					}
				}

			}
		}
	}

	@Extension
	public static final class RunListenerImpl extends RunListener<Run> {
		public RunListenerImpl() {
		}

		public void onStarted(Run r, TaskListener listener) {
		}

		public void onCompleted(Run r, TaskListener listener) {
			Map var4 = FanInReverseBuildTrigger.upstream2Trigger;
			ArrayList triggers;
			synchronized(FanInReverseBuildTrigger.upstream2Trigger) {
				Collection<FanInReverseBuildTrigger> _triggers = (Collection)FanInReverseBuildTrigger.upstream2Trigger.get(r.getParent());
				if (_triggers == null || _triggers.isEmpty()) {
					return;
				}

				triggers = new ArrayList(_triggers);
			}

			Iterator var8 = triggers.iterator();

			while(var8.hasNext()) {
				FanInReverseBuildTrigger trigger = (FanInReverseBuildTrigger)var8.next();
				if (trigger.shouldTrigger(r, listener)) {
					if (!((Job)trigger.job).isBuildable()) {
						listener.getLogger().println(Messages.BuildTrigger_Disabled(ModelHyperlinkNote.encodeTo(trigger.job)));
					} else {
						String name = ModelHyperlinkNote.encodeTo(trigger.job) + " #" + ((Job)trigger.job).getNextBuildNumber();
						if (ParameterizedJobMixIn.scheduleBuild2((Job)trigger.job, -1, new Action[]{new CauseAction(new UpstreamCause(r))}) != null) {
							listener.getLogger().println(Messages.BuildTrigger_Triggering(name));
						} else {
							listener.getLogger().println(Messages.BuildTrigger_InQueue(name));
						}
					}
				}
			}

		}
	}

	@Extension
	public static final class DescriptorImpl extends TriggerDescriptor {
		public DescriptorImpl() {
		}

		public String getDisplayName() {
			return "FanInReverseBuildTrigger";
		}

		public boolean isApplicable(Item item) {
			return item instanceof Job && item instanceof ParameterizedJob;
		}

		public AutoCompletionCandidates doAutoCompleteUpstreamProjects(@QueryParameter String value, @AncestorInPath Item self, @AncestorInPath ItemGroup container) {
			return AutoCompletionCandidates.ofJobNames(Job.class, value, self, container);
		}

		public FormValidation doCheckUpstreamProjects(@AncestorInPath Job project, @QueryParameter String value) {
			if (!project.hasPermission(Item.CONFIGURE)) {
				return FormValidation.ok();
			} else {
				StringTokenizer tokens = new StringTokenizer(hudson.Util.fixNull(value), ",");
				boolean hasProjects = false;

				while(tokens.hasMoreTokens()) {
					String projectName = tokens.nextToken().trim();
					if (StringUtils.isNotBlank(projectName)) {
						Jenkins jenkins = Jenkins.getInstance();
						if (jenkins == null) {
							return FormValidation.ok();
						}

						Job item = (Job)jenkins.getItem(projectName, project, Job.class);
						if (item == null) {
							Job nearest = (Job)Items.findNearest(Job.class, projectName, project.getParent());
							String alternative = nearest != null ? nearest.getRelativeNameFrom(project) : "?";
							return FormValidation.error(Messages.BuildTrigger_NoSuchProject(projectName, alternative));
						}

						hasProjects = true;
					}
				}

				if (!hasProjects) {
					return FormValidation.error(Messages.BuildTrigger_NoProjectSpecified());
				} else {
					return FormValidation.ok();
				}
			}
		}
	}

	public static class FanInDependency extends Dependency {
		public FanInDependency(AbstractProject upstream, AbstractProject downstream, String description) {
			super(upstream, downstream);
		}

		public String getDescription() {
			return "";
		}

		public boolean equals(Object obj) {
			if (obj == null) {
				return false;
			} else if (this.getClass() != obj.getClass()) {
				return false;
			} else {
				Dependency that = (Dependency)obj;
				return this.getUpstreamProject() == that.getUpstreamProject() || this.getDownstreamProject() == that.getDownstreamProject();
			}
		}

		public int hashCode() {
			int hash = 7;
			hash = 23 * hash + this.getUpstreamProject().hashCode();
			hash = 23 * hash + this.getDownstreamProject().hashCode();
			return hash;
		}

		public String toString() {
			return "";
		}
	}
}

/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tikal.hudson.plugins.notification;

import com.tikal.hudson.plugins.notification.changes.ChangesAggregator;
import com.tikal.hudson.plugins.notification.model.BuildState;
import com.tikal.hudson.plugins.notification.model.JobState;
import com.tikal.hudson.plugins.notification.model.ScmState;

import hudson.EnvVars;
import hudson.model.*;
import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;


@SuppressWarnings({ "unchecked", "rawtypes" })
public enum Phase {
    STARTED, COMPLETED, FINALIZED;

    transient List<ChangesAggregator> aggregators;

    @SuppressWarnings( "CastToConcreteClass" )
    public void handle(Run run, TaskListener listener) {

        HudsonNotificationProperty property = (HudsonNotificationProperty) run.getParent().getProperty(HudsonNotificationProperty.class);
        if ( property == null ){ return; }
        
        for ( Endpoint target : property.getEndpoints()) {
            if ( isRun( target )) {
                listener.getLogger().println( String.format( "Notifying endpoint '%s'", target ));

                try {
                    JobState jobState = buildJobState(run.getParent(), run, listener, target);
                    EnvVars environment = run.getEnvironment(listener);
                    String expandedUrl = environment.expand(target.getUrl());
                    target.getProtocol().send(expandedUrl,
                                              target.getFormat().serialize(jobState),
                                              target.getTimeout(),
                                              target.isJson());
                } catch (Throwable error) {
                    error.printStackTrace( listener.error( String.format( "Failed to notify endpoint '%s'", target )));
                    listener.getLogger().println( String.format( "Failed to notify endpoint '%s' - %s: %s",
                                                                 target, error.getClass().getName(), error.getMessage()));
                }
            }
        }
    }


    /**
     * Determines if the endpoint specified should be notified at the current job phase.
     */
    private boolean isRun( Endpoint endpoint ) {
        String event = endpoint.getEvent();
        return (( event == null ) || event.equals( "all" ) || event.equals( this.toString().toLowerCase()));
    }

    private JobState buildJobState(Job job, Run run, TaskListener listener, Endpoint target)
        throws IOException, InterruptedException
    {

        Jenkins            jenkins      = Jenkins.getInstance();
        String             rootUrl      = jenkins.getRootUrl();
        JobState           jobState     = new JobState();
        BuildState         buildState   = new BuildState();
        ScmState           scmState     = new ScmState();
        Result             result       = run.getResult();
        ParametersAction   paramsAction = run.getAction(ParametersAction.class);
        EnvVars            environment  = run.getEnvironment( listener );
        StringBuilder      log          = this.getLog(run, target);

        jobState.setName( job.getName());
        jobState.setUrl( job.getUrl());
        jobState.setBuild( buildState );

        buildState.setNumber( run.number );
        buildState.setUrl( run.getUrl());
        buildState.setPhase( this );
        buildState.setScm( scmState );
        buildState.setLog( log );

        if ( result != null ) {
            buildState.setStatus(result.toString());
        }

        if ( rootUrl != null ) {
            buildState.setFullUrl(rootUrl + run.getUrl());
        }

        buildState.updateArtifacts( job, run );

        if ( paramsAction != null ) {
            EnvVars env = new EnvVars();
            for (ParameterValue value : paramsAction.getParameters()){
                if ( ! value.isSensitive()) {
                    value.buildEnvironment( run, env );
                }
            }
            buildState.setParameters(env);
        }

        if ( environment.get( "GIT_URL" ) != null ) {
            scmState.setUrl( environment.get( "GIT_URL" ));
        }

        if ( environment.get( "GIT_BRANCH" ) != null ) {
            scmState.setBranch( environment.get( "GIT_BRANCH" ));
        }

        if ( environment.get( "GIT_COMMIT" ) != null ) {
            scmState.setCommit( environment.get( "GIT_COMMIT" ));
        }

        if ( run instanceof AbstractBuild ) {
            Multimap<Entry, AbstractBuild> allChanges = getAllChanges( (AbstractBuild) run );
            Iterator<Entry> iterator = allChanges.keys().iterator();
            String msg = "", paths = "";
            while ( iterator.hasNext() ) {
                Entry entry = iterator.next();
                msg += entry.getMsg() + "\n";
                paths += entry.getAffectedPaths().toString() + "\n";
            }
            scmState.setChanges( msg );
            scmState.setAffectedPaths( paths );
        }

        return jobState;
    }

    private StringBuilder getLog(Run run, Endpoint target) {
        StringBuilder log = new StringBuilder("");
        Integer loglines = target.getLoglines();

        if (null == loglines) {
                return log;
        }

        try {
            switch (loglines) {
                // The full log
                case -1:
                    log.append(run.getLog());
                    break;
                default:
                    List<String> logEntries = run.getLog(loglines);
                    for (String entry: logEntries) {
                        log.append(entry);
                        log.append("\n");
                    }
            }
        } catch (IOException e) {
            log.append("Unable to retrieve log");
        }
        return log;
    }

    /**
     * Returns all changes which contribute to a build.
     *
     * @param build
     * @return
     */
    public Multimap<ChangeLogSet.Entry, AbstractBuild> getAllChanges(AbstractBuild build) {
        Set<AbstractBuild> builds = getContributingBuilds(build);
        Multimap<String, ChangeLogSet.Entry> changes = ArrayListMultimap.create();
        for (AbstractBuild changedBuild : builds) {
            ChangeLogSet<ChangeLogSet.Entry> changeSet = changedBuild.getChangeSet();
            for (ChangeLogSet.Entry entry : changeSet) {
                changes.put(entry.getCommitId() + entry.getMsgAnnotated() + entry.getTimestamp(), entry);
            }
        }
        Multimap<ChangeLogSet.Entry, AbstractBuild> change2Build = HashMultimap.create();
        for (String changeKey : changes.keySet()) {
            ChangeLogSet.Entry change = changes.get(changeKey).iterator().next();
            for (ChangeLogSet.Entry entry : changes.get(changeKey)) {
                change2Build.put(change, entry.getParent().build);
            }
        }
        return change2Build;
    }

    /**
     * Uses all ChangesAggregators to calculate the contributing builds
     *
     * @return all changes which contribute to the given build
     */
    public Set<AbstractBuild> getContributingBuilds(AbstractBuild build) {
        if (aggregators == null) {
            aggregators = ImmutableList.copyOf(ChangesAggregator.all());
        }
        Set<AbstractBuild> builds = Sets.newHashSet();
        builds.add(build);
        int size = 0;
        // Saturate the build Set
        do {
            size = builds.size();
            Set<AbstractBuild> newBuilds = Sets.newHashSet();
            for (ChangesAggregator aggregator : aggregators) {
                for (AbstractBuild depBuild : builds) {
                    newBuilds.addAll(aggregator.aggregateBuildsWithChanges(depBuild));
                }
            }
            builds.addAll(newBuilds);
        } while (size < builds.size());
        return builds;
    }
}

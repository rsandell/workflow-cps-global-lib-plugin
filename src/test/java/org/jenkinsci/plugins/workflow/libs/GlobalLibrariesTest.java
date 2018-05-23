/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.libs;

import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.model.Item;
import hudson.model.View;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import hudson.plugins.git.browser.GitLab;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.GitBrowserSCMSourceTrait;
import jenkins.plugins.git.traits.IgnoreOnPushNotificationTrait;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.subversion.SubversionSCMSource;
import static org.hamcrest.core.IsCollectionContaining.hasItem;
import static org.hamcrest.Matchers.*;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.recipes.LocalData;

public class GlobalLibrariesTest {

    @Rule public JenkinsRule r = new JenkinsRule();

    @Test public void configRoundtrip() throws Exception {
        r.configRoundtrip();
        GlobalLibraries gl = GlobalLibraries.get();
        assertEquals(Collections.emptyList(), gl.getLibraries());
        LibraryConfiguration foo = new LibraryConfiguration("foo", new SCMSourceRetriever(new SubversionSCMSource("foo", "https://phony.jenkins.io/foo/")));
        LibraryConfiguration bar = new LibraryConfiguration("bar", new SCMSourceRetriever(new GitSCMSource(null, "https://phony.jenkins.io/bar.git", "", "origin", "+refs/heads/*:refs/remotes/origin/*", "*", "", true)));
        bar.setDefaultVersion("master");
        bar.setImplicit(true);
        bar.setAllowVersionOverride(false);
        gl.setLibraries(Arrays.asList(foo, bar));
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
            grant(Jenkins.ADMINISTER).everywhere().to("alice"). // includes RUN_SCRIPTS and all else
            grantWithoutImplication(Jenkins.ADMINISTER).everywhere().to("bob"). // but not RUN_SCRIPTS
            grant(Jenkins.READ, Item.READ, View.READ).everywhere().to("bob"));
        HtmlPage configurePage = r.createWebClient().login("alice").goTo("configure");
        assertThat(configurePage.getWebResponse().getContentAsString(), containsString("https://phony.jenkins.io/bar.git"));
        r.submit(configurePage.getFormByName("config")); // JenkinsRule.configRoundtrip expanded to include login
        List<LibraryConfiguration> libs = gl.getLibraries();
        r.assertEqualDataBoundBeans(Arrays.asList(foo, bar), libs);
        configurePage = r.createWebClient().login("bob").goTo("configure");
        assertThat(configurePage.getWebResponse().getContentAsString(), not(containsString("https://phony.jenkins.io/bar.git")));
        r.submit(configurePage.getFormByName("config"));
        libs = gl.getLibraries();
        r.assertEqualDataBoundBeans(Arrays.asList(foo, bar), libs);
    }

    @Issue("JENKINS-45747")
    @Test @LocalData
    public void loadOldGitConfig() {
        final GlobalLibraries libraries = GlobalLibraries.get();
        assertNotNull(libraries);
        assertThat(libraries.getLibraries(), hasSize(1));
        final LibraryConfiguration configuration = libraries.getLibraries().get(0);
        assertEquals(configuration.getName(), "jenkins-groovy-common");
        final SCMSourceRetriever retriever = (SCMSourceRetriever) configuration.getRetriever();
        assertNotNull(retriever);
        assertThat(retriever.getScm(), instanceOf(GitSCMSource.class));
        GitSCMSource scm = (GitSCMSource) retriever.getScm();
        assertEquals(scm.getRemote(), "git@mygitserver/jenkins-groovy-common.git");
        final List<? extends SCMSourceTrait> traits = scm.getTraits();
        //assertThat(traits, hasSize(2));
        assertThat(traits, new ListContains(new IsAnInstanceOf(IgnoreOnPushNotificationTrait.class)));
        assertThat(traits, new ListContains(allOf(
                new IsAnInstanceOf(GitBrowserSCMSourceTrait.class),
                hasProperty("browser", instanceOf(GitLab.class)))));

    }

    /**
     * Hamcrest matchers are awful when dealing with lists and generics
     */
    static class IsAnInstanceOf extends TypeSafeMatcher<SCMSourceTrait> {

        private final Class<? extends SCMSourceTrait> klass;

        public IsAnInstanceOf(Class<? extends SCMSourceTrait> klass) {
            this.klass = klass;
        }

        @Override
        protected boolean matchesSafely(SCMSourceTrait item) {
            return klass.isAssignableFrom(item.getClass());
        }


        @Override
        public void describeTo(Description description) {
            description.appendText(" instanceOf ");
            description.appendText(klass.getName());
        }


    }

    /**
     * Hamcrest matchers are awful when dealing with lists and generics
     */
    static class ListContains extends TypeSafeMatcher<List<? extends SCMSourceTrait>> {

        private final Matcher<? extends SCMSourceTrait> itemMatcher;

        public ListContains(Matcher<? extends SCMSourceTrait> itemMatcher) {
            this.itemMatcher = itemMatcher;
        }

        @Override
        protected boolean matchesSafely(List<? extends SCMSourceTrait> item) {
            for (Object t : item) {
                if (itemMatcher.matches(t)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("A list containing ");
            description.appendDescriptionOf(itemMatcher);
        }
    }

}

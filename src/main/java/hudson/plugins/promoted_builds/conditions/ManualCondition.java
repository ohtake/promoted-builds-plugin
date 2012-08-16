package hudson.plugins.promoted_builds.conditions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.Sets;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.InvisibleAction;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.User;
import hudson.plugins.promoted_builds.PromotionBadge;
import hudson.plugins.promoted_builds.PromotionCondition;
import hudson.plugins.promoted_builds.PromotionConditionDescriptor;
import hudson.plugins.promoted_builds.PromotionProcess;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.servlet.ServletException;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.acegisecurity.GrantedAuthority;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * {@link PromotionCondition} that requires manual promotion.
 *
 * @author Kohsuke Kawaguchi
 * @author Peter Hayes
 */
public class ManualCondition extends PromotionCondition {
    @VisibleForTesting
    String users;
    private List<ParameterDefinition> parameterDefinitions = new ArrayList<ParameterDefinition>();

    public ManualCondition() {
    }

    public String getUsers() {
        return users;
    }

    public List<ParameterDefinition> getParameterDefinitions() {
        return parameterDefinitions;
    }

    /**
     * Gets the {@link ParameterDefinition} of the given name, if any.
     */
    public ParameterDefinition getParameterDefinition(String name) {
        if (parameterDefinitions == null) {
            return null;
        }

        for (ParameterDefinition pd : parameterDefinitions)
            if (pd.getName().equals(name))
                return pd;
        
        return null;
    }

    /**
     *
     * @deprecated Use {@link #getAllowedUsersAsSet()} instead.
     */
    public Set<String> getUsersAsSet() {
        if (users == null || users.equals(""))
            return Collections.emptySet();

        Set<String> set = new HashSet<String>();
        for (String user : users.split(",")) {
            user = user.trim();

            if (user.trim().length() > 0)
                set.add(user);
        }
        
        return set;
    }

    public Set<String> getAllowedUsersAsSet() {
        Set<String> all = getUsersAsSet();
        Set<String> allowed = Sets.filter(all, new Predicate<String>() {
            public boolean apply(String input) {
                return !input.startsWith("!");
            }
        });
        return allowed;
    }
    public Set<String> getDisallowedUsersAsSet() {
        Set<String> all = getUsersAsSet();
        Set<String> disallowed = Sets.newHashSet();
        for(String u : all) {
            if(u.startsWith("!")) {
                String name = u.substring(1).trim();
                if(name.length() > 0) disallowed.add(name);
            }
        }
        return disallowed;
    }

    @Override
    public PromotionBadge isMet(PromotionProcess promotionProcess, AbstractBuild<?,?> build) {
        List<ManualApproval> approvals = build.getActions(ManualApproval.class);

        for (ManualApproval approval : approvals) {
            if (approval.name.equals(promotionProcess.getName()))
                return approval.badge;
        }

        return null;
    }

    /**
     * Verifies that the currently logged in user (or anonymous) has permission
     * to approve the promotion and that the promotion has not already been
     * approved.
     */
    public boolean canApprove(PromotionProcess promotionProcess, AbstractBuild<?,?> build) {
        Set<String> allowed = getAllowedUsersAsSet();
        Set<String> disallowed = getDisallowedUsersAsSet();
        if (!allowed.isEmpty() && !isInUsersList(allowed) && !isInGroupList(allowed)) {
            return false;
        }
        if (isInUsersList(disallowed) || isInGroupList(disallowed)) {
            return false;
        }

        List<ManualApproval> approvals = build.getActions(ManualApproval.class);

        // For now, only allow approvals if this wasn't already approved
        for (ManualApproval approval : approvals) {
            if (approval.name.equals(promotionProcess.getName()))
                return false;
        }

        return true;
    }

    /*
     * Check if user is listed in user list as a specific user
     */
    private boolean isInUsersList(Set<String> set) {
        // Current user must be in users list or users list is empty
        return set.contains(Hudson.getAuthentication().getName());
    }

    /*
     * Check if user is a member of a groups as listed in the user / group field
     */
    private boolean isInGroupList(Set<String> set) {
        GrantedAuthority[] authorities = Hudson.getAuthentication().getAuthorities();
        for (GrantedAuthority authority : authorities) {
            if (set.contains(authority.getAuthority()))
                return true;
        }
        return false;
    }

    /**
     * Web method to handle the approval action submitted by the user.
     */
    public void doApprove(StaplerRequest req, StaplerResponse rsp,
            @AncestorInPath PromotionProcess promotionProcess,
            @AncestorInPath AbstractBuild<?,?> build) throws IOException, ServletException {

	JSONObject formData = req.getSubmittedForm();

        if (canApprove(promotionProcess, build)) {
            List<ParameterValue> paramValues = new ArrayList<ParameterValue>();

            if (parameterDefinitions != null && !parameterDefinitions.isEmpty()) {
                JSONArray a = JSONArray.fromObject(formData.get("parameter"));

                for (Object o : a) {
                    JSONObject jo = (JSONObject) o;
                    String name = jo.getString("name");

                    ParameterDefinition d = getParameterDefinition(name);
                    if (d==null)
                        throw new IllegalArgumentException("No such parameter definition: " + name);

                    ParameterValue value = d.createValue(req, jo);

                    paramValues.add(d.createValue(req, jo));
                }
            }

            // add approval to build
            build.addAction(new ManualApproval(promotionProcess.getName(), paramValues));
            build.save();

            // check for promotion
            promotionProcess.considerPromotion2(build);
        }

        rsp.sendRedirect2("../../../..");
    }

    /*
     * Used to annotate the build to indicate that it was manually approved.  This
     * is then looked for in the isMet method.
     */
    public static final class ManualApproval extends InvisibleAction {
        public String name;
        public Badge badge;

        public ManualApproval(String name, List<ParameterValue> values) {
            this.name = name;
            badge = new Badge(values);
        }
    }

    public static final class Badge extends PromotionBadge {
        public String authenticationName;
        private final List<ParameterValue> values;

        public Badge(List<ParameterValue> values) {
            this.authenticationName = Hudson.getAuthentication().getName();
            this.values = values;
        }

        public String getUserName() {
            if (authenticationName == null)
                return "N/A";

            User u = User.get(authenticationName, false);
            return u != null ? u.getDisplayName() : authenticationName;
        }

        public List<ParameterValue> getParameterValues() {
            return values != null ? values : Collections.EMPTY_LIST;
        }

        @Override
        public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
            for (ParameterValue value : getParameterValues()) {
                value.buildEnvVars(build, env);
            }
        }
    }

    @Extension
    public static final class DescriptorImpl extends PromotionConditionDescriptor {
        public boolean isApplicable(AbstractProject<?,?> item) {
            return true;
        }

        public String getDisplayName() {
            return Messages.ManualCondition_DisplayName();
        }

        @Override
        public ManualCondition newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            ManualCondition instance = new ManualCondition();
            instance.users = formData.getString("users");
            instance.parameterDefinitions = Descriptor.newInstancesFromHeteroList(req, formData, "parameters", ParameterDefinition.all());
            return instance;
        }
    }
}


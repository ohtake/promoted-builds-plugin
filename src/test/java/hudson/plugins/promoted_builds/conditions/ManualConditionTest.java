package hudson.plugins.promoted_builds.conditions;

import com.google.common.collect.Sets;
import junit.framework.TestCase;
import org.junit.Assert;

import java.util.Set;


public class ManualConditionTest extends TestCase {
    private static final class UsersAndExpected {
        public String input;
        public Set<String> allowed;
        public Set<String> disallowed;

        public UsersAndExpected(String input, String[] allowed, String[] disallowed) {
            this.input = input;
            this.allowed = Sets.newHashSet(allowed);
            this.disallowed = Sets.newHashSet(disallowed);
        }
    }

    private UsersAndExpected[] data = new UsersAndExpected[] {
        new UsersAndExpected("",new String[]{},new String[]{}),
        new UsersAndExpected(" ,u1,  u2 ,  u3,",new String[]{"u1","u2","u3"},new String[]{}),
        new UsersAndExpected("u1, ! u2 ,!u3,u4",new String[]{"u1","u4"},new String[]{"u2","u3"}),
    };


    public void testGetAllowedUsersAsSet() throws Exception {
        ManualCondition c = new ManualCondition();
        for(UsersAndExpected d : data) {
            c.users = d.input;
            Set<String> actual = c.getAllowedUsersAsSet();
            Assert.assertTrue(d.allowed.containsAll(actual));
            Assert.assertTrue(actual.containsAll(d.allowed));
        }
    }

    public void testGetDisallowedUsersAsSet() throws Exception {
        ManualCondition c = new ManualCondition();
        for(UsersAndExpected d : data) {
            c.users = d.input;
            Set<String> actual = c.getDisallowedUsersAsSet();
            Assert.assertTrue(d.disallowed.containsAll(actual));
            Assert.assertTrue(actual.containsAll(d.disallowed));
        }
    }
}

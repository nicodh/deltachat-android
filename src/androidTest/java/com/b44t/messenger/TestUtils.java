package com.b44t.messenger;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.ViewInteraction;
import androidx.test.espresso.util.TreeIterables;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import org.hamcrest.Matcher;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.AccountManager;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.AccessibilityUtil;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;
import chat.delta.rpc.types.Account;

public class TestUtils {
  private static int createdAccountId = 0;
  private static final List<Integer> onlineAccountIds = new ArrayList<>();

  public static class AccountInfo {
    public final int id;
    public final String address;
    AccountInfo(int id, String address) {
      this.id = id;
      this.address = address;
    }
  }

  /**
   * Creates a fresh chatmail account by scanning a dcaccount: QR code.
   * The chatmail server auto-generates credentials — no pre-configured secrets needed.
   * This method blocks until configuration completes (up to 60 seconds).
   */
  public static AccountInfo createOnlineChatmailAccount(Context context) throws Exception {
    int[] accountId = {0};
    getInstrumentation().runOnMainSync(
            () -> accountId[0] = AccountManager.getInstance().beginAccountCreation(context));
    if (accountId[0] == 0) throw new RuntimeException("beginAccountCreation() returned 0");

    Rpc rpc = DcHelper.getRpc(context);
    Exception[] error = {null};
    CountDownLatch latch = new CountDownLatch(1);
    new Thread(() -> {
      try {
        rpc.addTransportFromQr(accountId[0], "dcaccount:ci-chatmail.testrun.org");
      } catch (RpcException e) {
        error[0] = e;
      } finally {
        latch.countDown();
      }
    }).start();

    if (!latch.await(60, TimeUnit.SECONDS)) {
      throw new RuntimeException("createOnlineChatmailAccount() timed out after 60s");
    }
    if (error[0] != null) throw error[0];

    Account accountInfo = rpc.getAccountInfo(accountId[0]);
    if (!(accountInfo instanceof Account.Configured)) {
      throw new RuntimeException("Account " + accountId[0] + " is not configured after addTransportFromQr");
    }
    Account.Configured info = (Account.Configured) accountInfo;
    onlineAccountIds.add(accountId[0]);
    return new AccountInfo(accountId[0], info.addr);
  }

  /**
   * Switches the currently selected account without touching the UI.
   * Call this before launching an Activity in tests.
   */
  public static void switchAccount(Context context, int accountId) {
    getInstrumentation().runOnMainSync(
            () -> AccountManager.getInstance().switchAccount(context, accountId));
  }

  /** Removes all accounts created via {@link #createOnlineChatmailAccount}. */
  public static void cleanupOnlineAccounts() {
    Context context = getInstrumentation().getTargetContext();
    DcAccounts accounts = DcHelper.getAccounts(context);
    for (int id : onlineAccountIds) {
      accounts.removeAccount(id);
    }
    onlineAccountIds.clear();
  }

  public static void cleanupCreatedAccount(Context context) {
    DcAccounts accounts = DcHelper.getAccounts(context);
    if (createdAccountId != 0) {
      accounts.removeAccount(createdAccountId);
      createdAccountId = 0;
    }
  }

  public static void cleanup() {
    cleanupCreatedAccount(getInstrumentation().getTargetContext());
  }

  public static void createOfflineAccount() {
    Context context = getInstrumentation().getTargetContext();
    cleanupCreatedAccount(context);
    createdAccountId = AccountManager.getInstance().beginAccountCreation(context);
    DcContext c = DcHelper.getContext(context);
    c.setConfig("configured_addr", "alice@example.org");
    c.setConfig("configured_mail_pw", "abcd");
    c.setConfig("configured", "1");
  }

  @NonNull
  public static ActivityScenarioRule<ConversationListActivity> getOfflineActivityRule(
      boolean useExistingChats) {
    Intent intent =
        Intent.makeMainActivity(
            new ComponentName(
                getInstrumentation().getTargetContext(), ConversationListActivity.class));
    if (!useExistingChats) {
      createOfflineAccount();
    }
    prepare();
    return new ActivityScenarioRule<>(intent);
  }

  @NonNull
  public static <T extends Activity> ActivityScenarioRule<T> getOnlineActivityRule(
      Class<T> activityClass) {
    Context context = getInstrumentation().getTargetContext();
    AccountManager.getInstance().beginAccountCreation(context);
    prepare();
    return new ActivityScenarioRule<>(
        new Intent(getInstrumentation().getTargetContext(), activityClass));
  }

  public static void prepare() {
    Context context = getInstrumentation().getTargetContext();
    Prefs.setBooleanPreference(context, Prefs.DOZE_ASKED_DIRECTLY, true);
    if (!AccessibilityUtil.areAnimationsDisabled(context)) {
      throw new RuntimeException(
          "To run the tests, disable animations at Developer options' "
              + "-> 'Window/Transition/Animator animation scale' -> Set all 3 to 'off'");
    }
  }

  /**
   * Perform action of waiting for a certain view within a single root view
   *
   * @param matcher Generic Matcher used to find our view
   */
  private static ViewAction searchFor(Matcher<View> matcher) {
    return new ViewAction() {

      public Matcher<View> getConstraints() {
        return isRoot();
      }

      public String getDescription() {
        return "searching for view $matcher in the root view";
      }

      public void perform(UiController uiController, View view) {

        Iterable<View> childViews = TreeIterables.breadthFirstViewTraversal(view);

        // Look for the match in the tree of childviews
        for (View it : childViews) {
          if (matcher.matches(it)) {
            // found the view
            return;
          }
        }

        throw new NoMatchingViewException.Builder()
            .withRootView(view)
            .withViewMatcher(matcher)
            .build();
      }
    };
  }

  /**
   * Perform action of implicitly waiting for a certain view. This differs from
   * EspressoExtensions.searchFor in that, upon failure to locate an element, it will fetch a new
   * root view in which to traverse searching for our @param match
   *
   * @param viewMatcher ViewMatcher used to find our view
   */
  public static ViewInteraction waitForView(
      Matcher<View> viewMatcher, int waitMillis, int waitMillisPerTry) {

    // Derive the max tries
    int maxTries = (int) (waitMillis / waitMillisPerTry);

    int tries = 0;

    for (int i = 0; i < maxTries; i++)
      try {
        // Track the amount of times we've tried
        tries++;

        // Search the root for the view
        onView(isRoot()).perform(searchFor(viewMatcher));

        // If we're here, we found our view. Now return it
        return onView(viewMatcher);

      } catch (Exception e) {
        if (tries == maxTries) {
          throw e;
        }
        Util.sleep(waitMillisPerTry);
      }

    throw new RuntimeException("Error finding a view matching $viewMatcher");
  }

  /**
   * Normally, you would do onView(withId(R.id.send_button)).perform(click()); to send the draft
   * message. However, in order to change the send button to the attach button while there is no
   * draft, the send button is made invisible and the attach button is made visible instead. This
   * confuses the test framework.<br>
   * <br>
   * So, this is a workaround for pressing the send button.
   */
  public static void pressSend() {
    onView(withId(R.id.send_button)).perform(click());
  }
}

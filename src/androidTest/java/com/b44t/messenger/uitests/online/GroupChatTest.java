package com.b44t.messenger.uitests.online;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.b44t.messenger.TestUtils;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;

import chat.delta.rpc.Rpc;

/**
 * Tests group chat creation and messaging between two chatmail accounts.
 * Alice creates a group and adds Bob; we verify Bob receives the message.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class GroupChatTest {

  @Rule
  public TestUtils.ScreenshotOnFailureRule screenshotRule = new TestUtils.ScreenshotOnFailureRule();

  private static TestUtils.AccountInfo alice;
  private static TestUtils.AccountInfo bob;
  private static int groupChatId;

  private static final String GROUP_NAME    = "Test Group";
  private static final String GROUP_MESSAGE = "Hello group from Alice!";

  @BeforeClass
  public static void setUpAccounts() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    TestUtils.prepare();

    alice = TestUtils.createOnlineChatmailAccount(context);
    bob   = TestUtils.createOnlineChatmailAccount(context);

    Rpc rpc = DcHelper.getRpc(context);
    rpc.setConfig(alice.id, "displayname", "Alice");
    rpc.setConfig(bob.id,   "displayname", "Bob");

    // Create Bob as a contact on Alice's side and add him to a group
    int aliceContactBobId = rpc.createContact(alice.id, bob.address, "Bob");
    groupChatId = rpc.createGroupChat(alice.id, GROUP_NAME, false);
    rpc.addContactToChat(alice.id, groupChatId, aliceContactBobId);

    // Pre-create Alice as a contact on Bob's side for readable display names
    rpc.createContact(bob.id, alice.address, "Alice");
  }

  @AfterClass
  public static void tearDown() {
    TestUtils.cleanupOnlineAccounts();
    TestUtils.cleanup();
  }

  @Test
  public void testAliceSendsGroupMessageBobReceivesIt() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    Rpc rpc = DcHelper.getRpc(context);

    // --- Alice sends a message to the group via RPC ---
    rpc.miscSendTextMessage(alice.id, groupChatId, GROUP_MESSAGE);
    TestUtils.waitForMsgDelivered(context, alice.id, groupChatId, 30_000);

    // --- Bob receives the group message (verified via UI) ---
    TestUtils.switchAccount(context, bob.id);
    try (ActivityScenario<ConversationListActivity> ignored =
                 ActivityScenario.launch(ConversationListActivity.class)) {

      // Wait for the group to appear in Bob's chat list by its name.
      // Note: the chat list preview shows "Alice: <message>" (sender prefix) for group chats,
      // so we match by group name, not message content.
      TestUtils.waitForView(withText(GROUP_NAME), 30_000, 500);

      // Open the group and verify the message text inside the conversation
      onView(withId(R.id.list))
              .perform(actionOnItem(hasDescendant(withText(GROUP_NAME)), click()));
      TestUtils.waitForView(withText(GROUP_MESSAGE), 10_000, 500);
    }
  }
}

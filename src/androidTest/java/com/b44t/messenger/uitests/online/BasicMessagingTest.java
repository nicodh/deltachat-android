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
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;

import chat.delta.rpc.Rpc;

/**
 * Sends a message between two fresh chatmail accounts and verifies receipt.
 * Accounts are created on ci-chatmail.testrun.org at test start and removed afterwards.
 * The chat is set up programmatically so tests focus on the messaging flow, not navigation.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class BasicMessagingTest {

  @Rule
  public TestUtils.ScreenshotOnFailureRule screenshotRule = new TestUtils.ScreenshotOnFailureRule();

  private static TestUtils.AccountInfo alice;
  private static TestUtils.AccountInfo bob;
  private static int aliceChatWithBob;
  private static int bobChatWithAlice;

  private static final String TEST_MESSAGE = "Hello from Alice to Bob!";
  private static final String TEST_REPLY   = "Hello back from Bob to Alice!";

  @BeforeClass
  public static void setUpAccounts() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    TestUtils.prepare();

    // Create two independent chatmail accounts
    alice = TestUtils.createOnlineChatmailAccount(context);
    bob   = TestUtils.createOnlineChatmailAccount(context);

    Rpc rpc = DcHelper.getRpc(context);
    // Give each account a readable display name for UI assertions
    rpc.setConfig(alice.id, "displayname", "Alice");
    rpc.setConfig(bob.id,   "displayname", "Bob");

    // Pre-create contacts and 1:1 chats on both sides so the chat list is populated
    int aliceContactBobId = rpc.createContact(alice.id, bob.address, "Bob");
    aliceChatWithBob = rpc.createChatByContactId(alice.id, aliceContactBobId);

    int bobContactAliceId = rpc.createContact(bob.id, alice.address, "Alice");
    bobChatWithAlice = rpc.createChatByContactId(bob.id, bobContactAliceId);
  }

  @AfterClass
  public static void tearDown() {
    TestUtils.cleanupOnlineAccounts();
    TestUtils.cleanup();
  }

  @Test
  public void test1_AliceSendsMessageToBob() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    Rpc rpc = DcHelper.getRpc(context);

    // --- Alice sends a message via RPC ---
    rpc.miscSendTextMessage(alice.id, aliceChatWithBob, TEST_MESSAGE);
    TestUtils.waitForMsgDelivered(context, alice.id, aliceChatWithBob, 30_000);

    // --- Bob receives the message (verified via UI) ---
    TestUtils.switchAccount(context, bob.id);
    try (ActivityScenario<ConversationListActivity> ignored =
                 ActivityScenario.launch(ConversationListActivity.class)) {

      // Wait for Alice's message to appear as the chat preview in the list
      TestUtils.waitForView(withText(TEST_MESSAGE), 30_000, 500);

      // Open the chat and verify the full message text is visible
      onView(withId(R.id.list))
              .perform(actionOnItem(hasDescendant(withText("Alice")), click()));
      TestUtils.waitForView(withText(TEST_MESSAGE), 5_000, 100);
    }
  }

  @Test
  public void test2_BobRepliesToAlice() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    Rpc rpc = DcHelper.getRpc(context);

    // --- Bob sends a reply via RPC ---
    rpc.miscSendTextMessage(bob.id, bobChatWithAlice, TEST_REPLY);
    TestUtils.waitForMsgDelivered(context, bob.id, bobChatWithAlice, 30_000);

    // --- Alice receives the reply (verified via UI) ---
    TestUtils.switchAccount(context, alice.id);
    try (ActivityScenario<ConversationListActivity> ignored =
                 ActivityScenario.launch(ConversationListActivity.class)) {

      TestUtils.waitForView(withText(TEST_REPLY), 30_000, 500);

      onView(withId(R.id.list))
              .perform(actionOnItem(hasDescendant(withText("Bob")), click()));
      TestUtils.waitForView(withText(TEST_REPLY), 5_000, 100);
    }
  }
}

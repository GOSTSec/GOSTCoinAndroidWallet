/*
 * Copyright 2020 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.*
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.NotificationsAdapter
import de.schildbach.wallet.ui.dashpay.notification.ContactViewHolder
import de.schildbach.wallet.ui.dashpay.widget.ContactRequestPane
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_dashpay_user.*
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.InteractionAwareActivity

class DashPayUserActivity : InteractionAwareActivity(),
        NotificationsAdapter.OnItemClickListener,
        ContactViewHolder.OnContactActionClickListener {

    private lateinit var viewModel: DashPayUserActivityViewModel
    private lateinit var dashPayViewModel: DashPayViewModel
    private val showContactHistoryDisclaimer by lazy {
        intent.getBooleanExtra(EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER, false)
    }
    private val notificationsAdapter: NotificationsAdapter by lazy {
        NotificationsAdapter(this,
                WalletApplication.getInstance().wallet, false, this,
                this, true, showContactHistoryDisclaimer)
    }

    companion object {
        private const val EXTRA_INIT_USER_DATA = "extra_init_user_data"
        private const val EXTRA_INIT_PROFILE_DATA = "extra_init_profile_data"
        private const val EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER = "extra_show_contact_history_disclaimer"

        const val REQUEST_CODE_DEFAULT = 0
        const val RESULT_CODE_OK = 1
        const val RESULT_CODE_CHANGED = 2

        @JvmStatic
        fun createIntent(context: Context, dashPayProfile: DashPayProfile): Intent {
            val intent = Intent(context, DashPayUserActivity::class.java)
            intent.putExtra(EXTRA_INIT_PROFILE_DATA, dashPayProfile)
            intent.putExtra(EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER, false)
            return intent
        }

        @JvmStatic
        fun createIntent(context: Context, usernameSearchResult: UsernameSearchResult, showContactHistoryDisclaimer: Boolean = false): Intent {
            val intent = Intent(context, DashPayUserActivity::class.java)
            intent.putExtra(EXTRA_INIT_USER_DATA, usernameSearchResult)
            intent.putExtra(EXTRA_SHOW_CONTACT_HISTORY_DISCLAIMER, showContactHistoryDisclaimer)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashpay_user)

        close.setOnClickListener { finish() }

        viewModel = ViewModelProvider(this).get(DashPayUserActivityViewModel::class.java)
        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)

        if (intent.hasExtra(EXTRA_INIT_USER_DATA)) {
            viewModel.userData = intent.getParcelableExtra(EXTRA_INIT_USER_DATA)
            updateContactRelationUi()
        } else {
            val dashPayProfile = intent.getParcelableExtra(EXTRA_INIT_PROFILE_DATA) as DashPayProfile
            viewModel.userData = UsernameSearchResult(dashPayProfile.username, dashPayProfile, null, null)
            viewModel.initUserData(dashPayProfile.username).observe(this, Observer {
                updateContactRelationUi()
            })
        }

        viewModel.userLiveData.observe(this, Observer {
            updateContactRelationUi()
        })
        viewModel.sendContactRequestState.observe(this, Observer {
            updateContactRelationUi()
        })
        viewModel.notificationsForUser.observe(this, Observer {
            if (Status.SUCCESS == it.status) {
                if (it.data != null) {
                    processResults(it.data)
                }
            }
        })

        val username = viewModel.userData.username
        val profile = viewModel.userData.dashPayProfile
        val displayName = profile.displayName

        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(this, username[0])
        if (profile.avatarUrl.isNotEmpty()) {
            Glide.with(this).load(profile.avatarUrl).circleCrop()
                    .placeholder(defaultAvatar).into(avatar)
        } else {
            avatar.background = defaultAvatar
        }
        if (displayName.isNotEmpty()) {
            displayNameTxt.text = displayName
            usernameTxt.text = username
        } else {
            displayNameTxt.text = username
        }
        contact_request_pane.setOnUserActionListener(object : ContactRequestPane.OnUserActionListener {

            override fun onSendContactRequestClick() {
                viewModel.sendContactRequest()
                setResult(RESULT_CODE_CHANGED)
            }

            override fun onAcceptClick() {
                viewModel.sendContactRequest()
                setResult(RESULT_CODE_CHANGED)
            }

            override fun onIgnoreClick() {
                Toast.makeText(this@DashPayUserActivity, "Not yet implemented", Toast.LENGTH_LONG).show()
            }

            override fun onPayClick() {
                startPayActivity()
            }
        })

        activity_rv.layoutManager = LinearLayoutManager(this)
        activity_rv.adapter = this.notificationsAdapter
    }

    private fun updateContactRelationUi() {
        val userData = viewModel.userData
        val state = viewModel.sendContactRequestState.value
        ContactRelation.process(viewModel.userData.type, state, object : ContactRelation.RelationshipCallback {
            override fun none() {
                if (showContactHistoryDisclaimer) {
                    contact_request_pane.applyDisclaimerState(userData.username)
                } else {
                    contact_request_pane.applySendState()
                }
                activity_rv.visibility = View.GONE
            }

            override fun inviting() {
                if (showContactHistoryDisclaimer) {
                    contact_request_pane.applyDisclaimerSendingState()
                } else {
                    contact_request_pane.applySendingState()
                }
            }

            override fun invited() {
                if (showContactHistoryDisclaimer) {
                    contact_request_pane.applySentStateWithDisclaimer(userData.username)
                } else {
                    contact_request_pane.applySentState()
                }
                activity_rv.visibility = View.VISIBLE
            }

            override fun inviteReceived() {
                contact_request_pane.applyReceivedState(userData.username)
                activity_rv.visibility = View.VISIBLE
            }

            override fun acceptingInvite() {
                contact_request_pane.applyAcceptingState()
            }

            override fun friends() {
                contact_request_pane.applyFriendsState()
                activity_rv.visibility = View.VISIBLE
            }

        })
        if (userData.type != UsernameSearchResult.Type.NO_RELATIONSHIP) {
            viewModel.initNotificationsForUser()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_bottom)
    }

    private fun startPayActivity() {
        handleString(viewModel.userData.dashPayProfile.userId, true, R.string.scan_to_pay_username_dialog_message)
        finish()
    }

    private fun handleString(input: String, fireAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input, true) {

            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    SendCoinsInternalActivity.start(this@DashPayUserActivity, paymentIntent, true)
                }
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                if (fireAction) {
                    dialog(this@DashPayUserActivity, null, errorDialogTitleResId, messageResId, *messageArgs)
                }
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                // ignore
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
                // ignore
            }
        }.parse()
    }

    override fun onItemClicked(view: View, notificationItem: NotificationItem) {
        when (notificationItem) {
            is NotificationItemContact -> {

            }
            is NotificationItemPayment -> {
                val transactionDetailsDialogFragment = TransactionDetailsDialogFragment.newInstance(notificationItem.tx!!.txId)
                transactionDetailsDialogFragment.show(supportFragmentManager, null)
            }
        }
    }

    override fun onAcceptRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        // do nothing
    }

    override fun onIgnoreRequest(usernameSearchResult: UsernameSearchResult, position: Int) {
        //do nothing if an item is clicked for now
    }

    private fun processResults(data: List<NotificationItem>) {

        val results = ArrayList<NotificationsAdapter.NotificationViewItem>()

        results.add(NotificationsAdapter.HeaderViewItem(1, R.string.notifications_profile_activity))
        data.forEach { results.add(NotificationsAdapter.NotificationViewItem(it, false)) }

        notificationsAdapter.results = results
    }
}
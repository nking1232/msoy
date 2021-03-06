//
// $Id$

package com.threerings.msoy.server;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import com.samskivert.util.Invoker;

import com.samskivert.jdbc.RepositoryUnit;

import com.threerings.presents.annotation.EventThread;
import com.threerings.presents.annotation.MainInvoker;
import com.threerings.presents.dobj.DSet;

import com.threerings.orth.peer.server.OrthPeerManager.FarSeeingObserver;

import com.threerings.msoy.data.MemberObject;
import com.threerings.msoy.data.all.FriendEntry;
import com.threerings.msoy.data.all.MemberName;
import com.threerings.msoy.peer.server.MsoyPeerManager;
import com.threerings.msoy.server.persist.MemberRepository;

import static com.threerings.msoy.Log.log;

/**
 * Handles management of member's friends, including their online status and adding and removing
 * friends.
 */
@Singleton @EventThread
public class FriendManager
    implements MemberLocator.Observer, FarSeeingObserver<MemberName>
{
    @Inject public FriendManager (MemberLocator locator)
    {
        // register to hear about member logon and logoff
        locator.addObserver(this);
    }

    /**
     * Prepares the friend manager for operation.
     */
    public void init ()
    {
        // register to hear when members log on and off of remote peers
        _peerMan.logObs.add(this);
    }

    /**
     * Called as a result of a friend being added.
     */
    public void addNewFriend (MemberObject memobj, FriendEntry entry)
    {
        int friendId = entry.name.getId();
        memobj.getLocal(MemberLocal.class).friendIds.add(friendId);
        registerFriendInterest(memobj, friendId);
        if (_peerMan.isMemberOnline(friendId)) {
            memobj.addToFriends(entry);
        }
    }

    /**
     * Called as a result of a friend being removed.
     */
    public void removeFriend (MemberObject memobj, int friendId)
    {
        memobj.getLocal(MemberLocal.class).friendIds.remove(friendId);
        clearFriendInterest(memobj, friendId);
        if (memobj.friends.containsKey(friendId)) {
            memobj.removeFromFriends(friendId);
        }
    }

    // from interface MemberLocator.Observer
    public void memberLoggedOn (final MemberObject memobj)
    {
        // register interest in updates for this member's friends
        for (int friendId : memobj.getLocal(MemberLocal.class).friendIds) {
            registerFriendInterest(memobj, friendId);
        }

        // determine which are online and then load their names and headlines
        final Set<Integer> onlineIds = _peerMan.filterOnline(memobj.getLocal(MemberLocal.class).friendIds);

        List<Integer> removeKeys = null;
        for (FriendEntry entry : memobj.friends) {
            Integer id = entry.name.getId();
            // remove onlineIds that we already have loaded
            if (!onlineIds.remove(id)) {
                if (removeKeys == null) {
                    removeKeys = Lists.newArrayList();
                }
                removeKeys.add(id);
            }
        }
        // usually we will have nothing to remove, which is why I avoid making a copy of the set
        if (removeKeys != null) {
            memobj.startTransaction();
            try {
                for (Integer id : removeKeys) {
                    memobj.removeFromFriends(id);
                }
            } finally {
                memobj.commitTransaction();
            }
        }

        if (onlineIds.isEmpty()) {
            return; // we're done
        }

        // Look up entries for all remaining friends
        _invoker.postUnit(new RepositoryUnit("load FriendEntrys") {
            public void invokePersist ()
                throws Exception
            {
                _entries = _memberRepo.loadFriendEntries(onlineIds);
            }

            public void handleSuccess ()
            {
                if (memobj.friends.size() == 0) {
                    // If we're a fresh login, and the invoker was slow, this could otherwise
                    // result in the client getting a "bla logged on" for every friend, when
                    // those friends were already online and the client was the new one.
                    // So we do this.
                    // We *may* sometimes have someone with no friends online, who switches
                    // nodes as one friend logs on, and they'll execute this code path and
                    // miss the online notification. Oh well. The friend will still show up in the
                    // list.
                    memobj.setFriends(new DSet<FriendEntry>(_entries));

                } else {
                    memobj.startTransaction();
                    try {
                        for (FriendEntry entry : _entries) {
                            memobj.addToFriends(entry);
                        }
                    } finally {
                        memobj.commitTransaction();
                    }
                }
            }

            protected FriendEntry[] _entries;
        });
    }

    // from interface MemberLocator.Observer
    public void memberLoggedOff (MemberObject memobj)
    {
        // clear out our friend interest registrations
        for (int friendId : memobj.getLocal(MemberLocal.class).friendIds) {
            clearFriendInterest(memobj, friendId);
        }
    }

    // from interface MsoyPeerManager.MemberObserver
    public void loggedOn (String nodeName, MemberName member)
    {
        final int memberId = member.getId();
        if (!_friendMap.containsKey(memberId)) {
            return; // we don't care
        }

        _invoker.postUnit(new RepositoryUnit("load FriendEntry") {
            public void invokePersist ()
                throws Exception
            {
                _entries = _memberRepo.loadFriendEntries(Collections.singleton(memberId));
            }

            public void handleSuccess ()
            {
                if (_entries.length > 0) {
                    memberLoggedOn(memberId, _entries[0]);
                }
            }

            protected FriendEntry[] _entries;
        });
    }

    // from interface MsoyPeerManager.MemberObserver
    public void loggedOff (String nodeName, MemberName member)
    {
        // TODO: maybe avoid when crossing nodes?
        Integer key = member.getId();
        for (MemberObject watcher : _friendMap.get(key)) {
            if (watcher.friends.containsKey(key)) {
                watcher.removeFromFriends(key);
            }
        }
    }

    // from interface MsoyPeerManager.MemberObserver
    public void memberEnteredScene (String nodeName, int memberId, int sceneId)
    {
        // nada
    }

    protected void registerFriendInterest (MemberObject memobj, int friendId)
    {
        _friendMap.put(friendId, memobj);
    }

    protected void clearFriendInterest (MemberObject memobj, int friendId)
    {
        if (!_friendMap.remove(friendId, memobj)) {
            log.warning("Watcher not listed when interest cleared?",
                "watcher", memobj.who(), "friend", friendId);
        }
    }

    /**
     * Helper method to handle the result of the other memberLoggedOn.
     */
    protected void memberLoggedOn (Integer memberId, FriendEntry entry)
    {
        int weird = 0;
        for (MemberObject watcher : _friendMap.get(memberId)) {
            if (watcher.friends.containsKey(memberId)) {
                watcher.updateFriends(entry);
                weird++;
            } else {
                watcher.addToFriends(entry);
            }
        }
        if (weird > 0) {
            // it would be extra weird if it only happened with some watchers
            log.info("That's weird.", "weirdness", weird + " / " + _friendMap.get(memberId).size());
        }
    }

    /** A mapping from member id to the member objects of members on this server that are friends
     * of the member in question. */
    protected Multimap<Integer,MemberObject> _friendMap = HashMultimap.create();

    @Inject protected @MainInvoker Invoker _invoker;
    @Inject protected MemberRepository _memberRepo;
    @Inject protected MsoyPeerManager _peerMan;
}

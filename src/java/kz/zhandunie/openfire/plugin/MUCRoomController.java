package kz.zhandunie.openfire.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.muc.CannotBeInvitedException;
import org.jivesoftware.openfire.muc.ConflictException;
import org.jivesoftware.openfire.muc.ForbiddenException;
import org.jivesoftware.openfire.muc.MUCRole;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.muc.NotAllowedException;
//import org.jivesoftware.openfire.utils.MUCRoomUtils;
import org.jivesoftware.util.StringUtils;
import org.xmpp.packet.JID;
import org.xmpp.packet.Presence;

/**
 * The Class MUCRoomController.
 */
public class MUCRoomController {
    /** The Constant INSTANCE. */
    public static final MUCRoomController INSTANCE = new MUCRoomController();

    /**
     * Gets the single instance of MUCRoomController.
     * 
     * @return single instance of MUCRoomController
     */
    public static MUCRoomController getInstance()
    {
        return INSTANCE;
    }

    private final UserRoomsCache userRoomsCache = new UserRoomsCache();

    public JID createRoom(String serviceName, JID ownerJID, String title, List<JID> members)
        throws NotAllowedException, ForbiddenException, ConflictException, CannotBeInvitedException
    {
        String roomname = generateRandomRoomname();

        MUCRoom room = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(serviceName)
                .getChatRoom(roomname, ownerJID);

        // Set our default options
        room.setNaturalLanguageName(title);
        room.setDescription("Created using mucextra plugin");
        room.setCanOccupantsChangeSubject(false);
        room.setMaxUsers(0);
        room.setPublicRoom(true);
        room.setPersistent(true);
        room.setModerated(false);
        room.setMembersOnly(true);
        room.setCanOccupantsInvite(false);
        room.setCanAnyoneDiscoverJID(true);
        room.setLogEnabled(true);
        room.setChangeNickname(false);
        room.setRegistrationEnabled(false);
        room.setModificationDate(new Date());
        room.setCreationDate(new Date());

        updateAffiliations(room, members, MUCRole.Affiliation.member);

        room.unlock(room.getRole());
        room.saveToDB();

        JID roomJID = room.getJID();

        userRoomsCache.userJoinedRoom(ownerJID, roomJID);

        return roomJID;
    }

    public void addRoomMembers(JID roomJID, JID adderJID, List<JID> members)
        throws CannotBeInvitedException, ConflictException, ForbiddenException, IllegalArgumentException,
            NotAllowedException, UnauthorizedException
    {
        MUCRoom room = getRoomByJID(roomJID);

        if (!isUserRoomOwner(adderJID, room) && !isUserRoomMember(adderJID, room)) {
            throw new UnauthorizedException("User with jid: " + adderJID +
                    " is not authorized to add new members to room with jid: " + roomJID);
        }

        updateAffiliations(room, members, MUCRole.Affiliation.member);
    }

    public void kickRoomMembers(JID roomJID, JID kickerJID, List<JID> members)
        throws CannotBeInvitedException, ConflictException, ForbiddenException, IllegalArgumentException,
            NotAllowedException, UnauthorizedException
    {
        MUCRoom room = getRoomByJID(roomJID);

        if (!isUserRoomOwner(kickerJID, room)) {
            throw new UnauthorizedException("User with jid: " + kickerJID +
                    " is not authorized to kick members from room with jid: " + roomJID);
        }

        updateAffiliations(room, members, MUCRole.Affiliation.none);
    }

    public void leaveRoom(JID roomJID, JID leaverJID)
        throws CannotBeInvitedException, ConflictException, ForbiddenException, IllegalArgumentException,
            NotAllowedException, UnauthorizedException
    {
        MUCRoom room = getRoomByJID(roomJID);

        if (isUserRoomOwner(leaverJID, room) && room.getOwners().size() == 1) {
            throw new NotAllowedException("User with jid: " + leaverJID +
                    "is the only owner of the room with jid: " + roomJID + ". He is not allowed to leave.");
        }

        if (!isUserRoomMember(leaverJID, room)) {
            throw new UnauthorizedException("User with jid: " + leaverJID +
                    "is not member of the room with jid: " + roomJID);
        }

        List<JID> members = new ArrayList<JID>(1);
        members.add(leaverJID);
        updateAffiliations(room, members, MUCRole.Affiliation.none);
    }

    public void deleteRoom(JID roomJID, JID deleterJID)
        throws IllegalArgumentException, UnauthorizedException
    {
        MUCRoom room = getRoomByJID(roomJID);

        if (!isUserRoomOwner(deleterJID, room)) {
            throw new UnauthorizedException("User with jid: " + deleterJID +
                    " is not authorized to delete room with jid: " + roomJID);
        }

        for (JID ownerJID : room.getOwners()) {
            userRoomsCache.userLeftRoom(ownerJID, roomJID);
        }

        for (JID memberJID : room.getMembers()) {
            userRoomsCache.userLeftRoom(memberJID, roomJID);
        }

        room.destroyRoom(null, null);
    }

    public void changeRoomTitle(JID roomJID, JID changerJID, String title)
        throws IllegalArgumentException, UnauthorizedException
    {
        MUCRoom room = getRoomByJID(roomJID);

        if (!isUserRoomOwner(changerJID, room) && !isUserRoomMember(changerJID, room)) {
            throw new UnauthorizedException("User with jid: " + changerJID +
                    " is not authorized to change title for room with jid: " + roomJID);
        }

        room.setNaturalLanguageName(title);
    }

    public List<MUCRoom> getUserRooms(JID userJID, String serviceName)
    {
        MultiUserChatService service = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(serviceName);

        Set<JID> roomJIDs = userRoomsCache.getRoomsForUser(userJID);
        if (roomJIDs == null) {
            roomJIDs = new HashSet<JID>();

            for (MUCRoom room : service.getChatRooms()) {
                if (isUserRoomOwner(userJID, room) || isUserRoomMember(userJID, room)) {
                    roomJIDs.add(room.getJID());
                }
            }

            userRoomsCache.createCacheForUser(userJID, roomJIDs);
        }

        List<MUCRoom> rooms = new ArrayList(roomJIDs.size());
        for (JID roomJID : roomJIDs) {
            MUCRoom room = service.getChatRoom(roomJID.getNode());
            if (room != null) {
                rooms.add(room);
            }
        }

        return rooms;
    }

    public List<JID> getRoomMembers(JID roomJID, JID userJID)
        throws IllegalArgumentException, UnauthorizedException
    {
        MUCRoom room = getRoomByJID(roomJID);

        if (!isUserRoomOwner(userJID, room) && !isUserRoomMember(userJID, room)) {
            throw new UnauthorizedException("User with jid: " + userJID +
                    " is not authorized to get the list of members for room with jid: " + roomJID);
        }

        Collection<JID> owners = room.getOwners();
        Collection<JID> members = room.getMembers();
        List<JID> all = new ArrayList<JID>(owners.size() + members.size());
        all.addAll(owners);
        all.addAll(members);

        return all;
    }

    private static final int ROOMNAME_LENGTH = 20;
    private static String generateRandomRoomname()
    {
        return StringUtils.randomString(ROOMNAME_LENGTH).toLowerCase();
    }

    private static String getServiceNameFromRoomJID(JID roomJID)
        throws IllegalArgumentException
    {
        String[] domainComponents = roomJID.getDomain().split("\\.");
        if (domainComponents.length < 2) {
            throw new IllegalArgumentException("MUC room domain must be of the form service.domain");
        }
        return domainComponents[0];
    }

    private void updateAffiliations(MUCRoom room, List<JID> members, MUCRole.Affiliation newAffiliation)
        throws ConflictException, ForbiddenException, CannotBeInvitedException, NotAllowedException
    {
        JID roomJID = room.getJID();
        List<Presence> presences = new ArrayList<Presence>(members.size());

        Collection<JID> owners = room.getOwners();
        for (JID memberJID : members) {
            // Don't modify owners
            boolean isOwner = false;
            for (JID ownerJID : owners) {
                if (ownerJID.equals(memberJID.asBareJID())) {
                    isOwner = true;
                    break;
                }
            }

            if (!isOwner) {
                if (newAffiliation == MUCRole.Affiliation.member) {
                    // Add member
                    presences.addAll(room.addMember(memberJID, null, room.getRole()));
                    room.sendInvitation(memberJID, null, room.getRole(), null);
                    userRoomsCache.userJoinedRoom(memberJID, roomJID);
                } else if (newAffiliation == MUCRole.Affiliation.none) {
                    // Remove member
                    presences.addAll(room.addNone(memberJID, room.getRole()));
                    userRoomsCache.userLeftRoom(memberJID, roomJID);
                }
            }
        }

        // Send the updated presences to the room occupants
        for (Presence presence : presences) {
            room.send(presence);
        }
    }

    private static MUCRoom getRoomByJID(JID roomJID)
        throws IllegalArgumentException
    {
        String roomName = roomJID.getNode();
        String serviceName = getServiceNameFromRoomJID(roomJID);
        MultiUserChatService mucService = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(serviceName);
        if (mucService == null) {
            throw new IllegalArgumentException("MUC service: " + serviceName + " for roomJID: " + roomJID + " was not found");
        }

        MUCRoom room = mucService.getChatRoom(roomName);
        if (room == null) {
            throw new IllegalArgumentException("Chat room with jid: " + roomJID + " was not found");
        }

        return room;
    }

    private static boolean isUserRoomMember(JID userJID, MUCRoom room)
    {
        JID bareUserJID = userJID.asBareJID();
        for (JID memberJID : room.getMembers()) {
            if (memberJID.asBareJID().equals(bareUserJID)) return true;
        }
        return false;
    }

    private static boolean isUserRoomOwner(JID userJID, MUCRoom room)
    {
        JID bareUserJID = userJID.asBareJID();
        for (JID memberJID : room.getOwners()) {
            if (memberJID.asBareJID().equals(bareUserJID)) return true;
        }
        return false;
    }

}

class UserRoomsCache {
    private Map<String, Set<JID>> userRooms = new HashMap<String, Set<JID>>();

    public void userJoinedRoom(JID userJID, JID roomJID)
    {
        Set<JID> rooms = userRooms.get(userJID.toBareJID());
        if (rooms != null) {
            rooms.add(roomJID);
        }
    }

    public void userLeftRoom(JID userJID, JID roomJID)
    {
        Set<JID> rooms = userRooms.get(userJID.toBareJID());
        if (rooms != null) {
            rooms.remove(roomJID);
        }
    }

    public boolean hasCacheForUser(JID userJID)
    {
        return userRooms.containsKey(userJID.toBareJID());
    }

    public void createCacheForUser(JID userJID, Set<JID> rooms)
    {
        userRooms.put(userJID.toBareJID(), rooms);
    }

    public Set<JID> getRoomsForUser(JID userJID)
    {
        return userRooms.get(userJID.toBareJID());
    }
}

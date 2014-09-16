package kz.zhandunie.openfire.plugin;

import java.io.File;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.dom4j.Element;
import org.jivesoftware.openfire.PacketRouter;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.group.Group;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.muc.CannotBeInvitedException;
import org.jivesoftware.openfire.muc.ConflictException;
import org.jivesoftware.openfire.muc.ForbiddenException;
import org.jivesoftware.openfire.muc.NotAllowedException;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.Component;
import org.xmpp.component.ComponentException;
import org.xmpp.component.ComponentManager;
import org.xmpp.component.ComponentManagerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

/**
 * The Class MUCExtraPlugin.
 */
public class MUCExtraPlugin implements Plugin, Component {
    private static final Logger Log = LoggerFactory.getLogger(MUCExtraPlugin.class);

    // Ideally we would want to be flexible to work with any MUC service created on the server.
    // For now we are just using service "conference" which is created by default in Openfire.
    private static final String MUC_SERVICE_NAME = "conference";

    // Users should be able to interact with this service by sending Packets to extra.muc_service_name.domain
    private static final String MUC_EXTRA_SERVICE_NAME = "extra." + MUC_SERVICE_NAME;

    private static final String COMPONENT_NAMESPACE = "http://jabber.org/protocol/muc#extra";

    private PluginManager pluginManager;
    private ComponentManager componentManager;
    private final PacketRouter router;
    private final MUCRoomController roomCtrl;

    /**
     * Instantiates a new MUC extra plugin.
     */
    public MUCExtraPlugin() {
        router = XMPPServer.getInstance().getPacketRouter();
        roomCtrl = MUCRoomController.getInstance();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.jivesoftware.openfire.container.Plugin#initializePlugin(org.jivesoftware
     * .openfire.container.PluginManager, java.io.File)
     */
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        pluginManager = manager;
        //interceptorManager.addInterceptor(this);

        // Register as a component.
        componentManager = ComponentManagerFactory.getComponentManager();
        try {
            componentManager.addComponent(MUC_EXTRA_SERVICE_NAME, this);
        }
        catch (Exception e) {
            Log.error(e.getMessage(), e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.jivesoftware.openfire.container.Plugin#destroyPlugin()
     */
    public void destroyPlugin() {
        //interceptorManager.removeInterceptor(this);
        if (componentManager != null) {
            try {
                componentManager.removeComponent(MUC_EXTRA_SERVICE_NAME);
            }
            catch (Exception e) {
                Log.error(e.getMessage(), e);
            }
        }
        componentManager = null;
        pluginManager = null;
    }

    public void initialize(JID jid, ComponentManager componentManager) {
    }

    public void start() {
    }

    public void shutdown() {
    }

    // Component Interface

    public String getName() {
        // Get the name from the plugin.xml file.
        return pluginManager.getName(this);
    }

    public String getDescription() {
        // Get the description from the plugin.xml file.
        return pluginManager.getDescription(this);
    }

    public void processPacket(Packet packet) {
        if (packet instanceof IQ) {
            processIQ((IQ)packet);
        }
    }

    private void processIQ(IQ iq)
    {
        Element query = iq.getChildElement();

        if (query == null || !COMPONENT_NAMESPACE.equals(query.getNamespaceURI())) {
            //System.out.println("query = " + query);
            sendErrorPacket(iq, PacketError.Condition.bad_request);
            return;
        }

        try {
            IQ resultIQ = IQ.createResultIQ(iq);
            Element response = resultIQ.setChildElement("query", COMPONENT_NAMESPACE);

            if (IQ.Type.set == iq.getType()) {
                if (query.element("newRoom") != null) {
                    handleNewRoomQuery(iq, query, response);
                } else if (query.element("deleteRoom") != null) {
                    handleDeleteRoomQuery(iq, query, response);
                } else if (query.element("addRoomMembers") != null) {
                    handleAddRoomMembersQuery(iq, query, response);
                } else if (query.element("kickRoomMembers") != null) {
                    handleKickRoomMembersQuery(iq, query, response);
                } else if (query.element("changeRoomTitle") != null) {
                    handleChangeRoomTitleQuery(iq, query, response);
                } else if (query.element("leaveRoom") != null) {
                    handleLeaveRoomQuery(iq, query, response);
                } else {
                    //System.out.println("query = " + query);
                    sendErrorPacket(iq, PacketError.Condition.bad_request);
                }
            } else if (IQ.Type.get == iq.getType()) {
                if (query.element("getRooms") != null) {
                    handleGetRoomsQuery(iq, query, response);
                } else if (query.element("getRoomMembers") != null) {
                    handleGetRoomMembersQuery(iq, query, response);
                } else {
                    //System.out.println("query = " + query);
                    sendErrorPacket(iq, PacketError.Condition.bad_request);
                }
            }

            if (resultIQ.getTo() != null) {
                router.route(resultIQ);
            }
        } catch (NotAllowedException e) {
            sendErrorPacket(iq, PacketError.Condition.not_allowed);
        } catch (ForbiddenException e) {
            sendErrorPacket(iq, PacketError.Condition.forbidden);
        } catch (ConflictException e) {
            sendErrorPacket(iq, PacketError.Condition.conflict);
        } catch (IllegalArgumentException e) {
            sendErrorPacket(iq, PacketError.Condition.item_not_found);
        } catch (UnauthorizedException e) {
            sendErrorPacket(iq, PacketError.Condition.not_authorized);
        } catch (CannotBeInvitedException e) {
            sendErrorPacket(iq, PacketError.Condition.not_acceptable);
        }
    }

    private void handleNewRoomQuery(IQ iq, Element query, Element response)
        throws NotAllowedException, ForbiddenException, ConflictException, CannotBeInvitedException
    {
        JID creatorJID = iq.getFrom();
        Element newRoom = query.element("newRoom");
        String title = newRoom.attributeValue("title");
        List<JID> memberJIDs = getMemberJIDs(newRoom);

        JID newRoomJID = roomCtrl.createRoom(MUC_SERVICE_NAME, creatorJID, title,  memberJIDs);

        response.addElement("newRoom")
                .addAttribute("jid", newRoomJID.toBareJID())
                .addAttribute("title", title)
                .addAttribute("owner", creatorJID.toBareJID());

        //System.out.println("newRoom: jid = " + newRoomJID + ", title = " + title + ", members = " + memberJIDs);
    }

    private void handleDeleteRoomQuery(IQ iq, Element query, Element response)
        throws IllegalArgumentException, UnauthorizedException
    {
        JID deleterJID = iq.getFrom();
        JID roomJID = new JID(query.element("deleteRoom").attributeValue("jid"));

        roomCtrl.deleteRoom(roomJID, deleterJID);

        //System.out.println("deleteRoom: jid = " + roomJID);
    }

    private void handleAddRoomMembersQuery(IQ iq, Element query, Element response)
        throws CannotBeInvitedException, ConflictException, ForbiddenException, IllegalArgumentException,
            NotAllowedException, UnauthorizedException
    {
        JID adderJID = iq.getFrom();
        Element addRoomMembers = query.element("addRoomMembers");
        JID roomJID = new JID(addRoomMembers.attributeValue("jid"));
        List<JID> members = getMemberJIDs(addRoomMembers);

        roomCtrl.addRoomMembers(roomJID, adderJID, members);

        //System.out.println("addRoomMembers: jid = " + roomJID + ", members = " + members);
    }

    private void handleKickRoomMembersQuery(IQ iq, Element query, Element response)
        throws CannotBeInvitedException, ConflictException, ForbiddenException, IllegalArgumentException,
            NotAllowedException, UnauthorizedException
    {
        JID kickerJID = iq.getFrom();
        Element kickRoomMembers = query.element("kickRoomMembers");
        JID roomJID = new JID(kickRoomMembers.attributeValue("jid"));
        List<JID> members = getMemberJIDs(kickRoomMembers);

        roomCtrl.kickRoomMembers(roomJID, kickerJID, members);

        //System.out.println("kickRoomMembers: jid = " + roomJID + ", members = " + members);
    }

    private void handleLeaveRoomQuery(IQ iq, Element query, Element response)
        throws CannotBeInvitedException, ConflictException, ForbiddenException, IllegalArgumentException,
            NotAllowedException, UnauthorizedException
    {
        JID leaverJID = iq.getFrom();
        JID roomJID = new JID(query.element("leaveRoom").attributeValue("jid"));

        roomCtrl.leaveRoom(roomJID, leaverJID);

        //System.out.println("leaveRoom: jid = " + roomJID);
    }

    private void handleChangeRoomTitleQuery(IQ iq, Element query, Element response)
        throws IllegalArgumentException, UnauthorizedException
    {
        Element changeRoomTitle = query.element("changeRoomTitle");
        JID changerJID = iq.getFrom();
        JID roomJID = new JID(changeRoomTitle.attributeValue("jid"));
        String title = changeRoomTitle.attributeValue("title");

        roomCtrl.changeRoomTitle(roomJID, changerJID, title);

        //System.out.println("changeRoomTitle: jid = " + roomJID + ", title = " + title);
    }

    private void handleGetRoomsQuery(IQ iq, Element query, Element response) {
        JID userJID = iq.getFrom();

        Element getRoomsResponse = response.addElement("getRooms");
        List<MUCRoom> rooms = roomCtrl.getUserRooms(userJID, MUC_SERVICE_NAME);
        for (MUCRoom room : rooms) {
            JID ownerJID = null;
            for (JID jid : room.getOwners()) {
                ownerJID = jid;
                break;
            }
            getRoomsResponse.addElement("room")
                            .addAttribute("jid", room.getJID().toBareJID())
                            .addAttribute("title", room.getNaturalLanguageName())
                            .addAttribute("owner", ownerJID.toBareJID());
        }

        //System.out.println("getRooms: from = " + userJID);
    }

    private void handleGetRoomMembersQuery(IQ iq, Element query, Element response)
        throws IllegalArgumentException, UnauthorizedException
    {
        JID userJID = iq.getFrom();
        JID roomJID = new JID(query.element("getRoomMembers").attributeValue("jid"));

        Element getRoomMembersResult = response.addElement("getRoomMembers");
        getRoomMembersResult.addAttribute("jid", roomJID.toBareJID());
        List<JID> members = roomCtrl.getRoomMembers(roomJID, userJID);
        for (JID memberJID : members) {
            getRoomMembersResult.addElement("member")
                                .addAttribute("jid", memberJID.toBareJID());
        }

        //System.out.println("getRoomMembers: jid = " + roomJID);
    }

    private List<JID> getMemberJIDs(Element element) {
        List<Element> members = element.elements("member");
        List<JID> memberJIDs = new ArrayList<JID>();
        for (Element member : members) {
            memberJIDs.add(new JID(member.attributeValue("jid")));
        }
        return memberJIDs;
    }

    /**
     * Generate an error packet for a given packet.
     * 
     * @param packet the packet to be bounced.
     * @param error the reason why the operation failed.
     */
    private void sendErrorPacket(Packet packet, PacketError.Condition error) {
        if (packet instanceof IQ) {
            IQ reply = IQ.createResultIQ((IQ) packet);
            reply.setChildElement(((IQ) packet).getChildElement().createCopy());
            reply.setError(error);
            router.route(reply);
        }
        else {
            Packet reply = packet.createCopy();
            reply.setError(error);
            reply.setFrom(packet.getTo());
            reply.setTo(packet.getFrom());
            router.route(reply);
        }
    }
}

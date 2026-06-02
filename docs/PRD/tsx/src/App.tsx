import { useState, useEffect } from 'react';
import { 
  Contact, 
  Conversation, 
  Message, 
  ActiveView, 
  ActiveTab 
} from './types';
import { 
  CURRENT_USER, 
  INITIAL_CONTACTS, 
  getInitialConversations 
} from './data';
import LoginRegister from './components/LoginRegister';
import BottomNavBar from './components/BottomNavBar';
import MessagesTab from './components/MessagesTab';
import ContactsTab from './components/ContactsTab';
import MeTab from './components/MeTab';
import ProfileDetails from './components/ProfileDetails';
import GroupChatCreate from './components/GroupChatCreate';
import ChatRoom from './components/ChatRoom';

export default function App() {
  // 1. Core States with local persistence bindings
  const [currentUser, setCurrentUser] = useState<Contact>(() => {
    const saved = localStorage.getItem('byteim_user');
    return saved ? JSON.parse(saved) : CURRENT_USER;
  });

  const [contacts, setContacts] = useState<Contact[]>(() => {
    const saved = localStorage.getItem('byteim_contacts');
    return saved ? JSON.parse(saved) : INITIAL_CONTACTS;
  });

  const [conversations, setConversations] = useState<Conversation[]>(() => {
    const saved = localStorage.getItem('byteim_conversations');
    return saved ? JSON.parse(saved) : getInitialConversations();
  });

  const [activeView, setActiveView] = useState<ActiveView>(() => {
    const saved = localStorage.getItem('byteim_active_view');
    // If user was logged in previously, restore active view, otherwise start with Login
    const loggedIn = localStorage.getItem('byteim_logged_in');
    if (loggedIn === 'true') {
      return (saved as ActiveView) || 'MAIN';
    }
    return 'LOGIN';
  });

  const [activeTab, setActiveTab] = useState<ActiveTab>(() => {
    const saved = localStorage.getItem('byteim_active_tab');
    return (saved as ActiveTab) || 'MESSAGES';
  });

  const [activeConversationId, setActiveConversationId] = useState<string | null>(() => {
    return localStorage.getItem('byteim_active_convo_id');
  });

  // 2. LocalStorage Sync Effector
  useEffect(() => {
    localStorage.setItem('byteim_user', JSON.stringify(currentUser));
  }, [currentUser]);

  useEffect(() => {
    localStorage.setItem('byteim_contacts', JSON.stringify(contacts));
  }, [contacts]);

  useEffect(() => {
    localStorage.setItem('byteim_conversations', JSON.stringify(conversations));
  }, [conversations]);

  useEffect(() => {
    localStorage.setItem('byteim_active_view', activeView);
    localStorage.setItem('byteim_logged_in', activeView !== 'LOGIN' ? 'true' : 'false');
  }, [activeView]);

  useEffect(() => {
    localStorage.setItem('byteim_active_tab', activeTab);
  }, [activeTab]);

  useEffect(() => {
    if (activeConversationId) {
      localStorage.setItem('byteim_active_convo_id', activeConversationId);
    } else {
      localStorage.removeItem('byteim_active_convo_id');
    }
  }, [activeConversationId]);

  // 3. User Success Login Handler
  const handleLoginSuccess = (phone: string) => {
    // Optionally update user phone if they entered a custom one
    if (phone.trim()) {
      setCurrentUser(prev => ({
        ...prev,
        phone: phone.trim()
      }));
    }
    setActiveView('MAIN');
    setActiveTab('MESSAGES');
  };

  const handleLogout = () => {
    if (confirm('Are you sure you want to log out of ByteIM?')) {
      setActiveView('LOGIN');
      setActiveConversationId(null);
      localStorage.removeItem('byteim_logged_in');
    }
  };

  // 4. Friend Profile Creator Handler
  const handleAddContact = (newContact: Contact) => {
    setContacts(prev => [...prev, newContact]);
  };

  // 5. Active Chat Discussion selector
  const handleSelectConversation = (convoId: string) => {
    setActiveConversationId(convoId);
    setActiveView('CHAT');

    // Instantly clear unread count for this thread
    setConversations(prev => 
      prev.map(c => c.id === convoId ? { ...c, unreadCount: 0 } : c)
    );
  };

  // 6. Direct click contact row => starts CHAT discussion
  const handleSelectContactChat = (contactId: string) => {
    const contact = contacts.find(c => c.id === contactId);
    if (!contact) return;

    // Check if conversation already exists
    const existing = conversations.find(c => !c.isGroup && c.id === contactId);
    if (existing) {
      handleSelectConversation(existing.id);
      return;
    }

    // Otherwise, create a beautiful standard private thread
    const newConvo: Conversation = {
      id: contact.id,
      name: contact.name,
      isGroup: false,
      avatar: contact.avatar.startsWith('initials:') ? '' : contact.avatar,
      unreadCount: 0,
      lastMessage: "Secure chat channel established.",
      lastMessageTime: "Today",
      messages: [
        {
          id: `channel_init_${Date.now()}`,
          senderId: 'system',
          senderName: 'System',
          senderAvatar: 'bell',
          text: `Chat established with ${contact.name}. Secure, private, and entirely yours.`,
          timestamp: 'Today',
          isSelf: false,
          isSystem: true
        }
      ],
      members: ['me', contact.id]
    };

    setConversations(prev => [newConvo, ...prev]);
    setActiveConversationId(newConvo.id);
    setActiveView('CHAT');
  };

  // 7. Group Creation Finished Action
  const handleGroupChatDone = (selectedContacts: Contact[]) => {
    if (selectedContacts.length === 0) return;

    const groupConvoId = `group_convo_${Date.now()}`;
    const groupName = `${selectedContacts.slice(0, 2).map(c => c.name.split(' ')[0]).join(', ')} ... & Sync`;
    
    // Aggregate member avatars
    const groupAvatars = selectedContacts.slice(0, 3).map(c => {
      return c.avatar.startsWith('initials:') 
        ? 'https://lh3.googleusercontent.com/aida-public/AB6AXuBPM1yNXlMwv0WdwL6nptRAh5sudHoKZGe9LQMAShLjDuxqEfjPQ_dqkm-pAFvS2e67doj6p5hA6ih_3xqXynUvPelBqayh9WWrQ7VR9Z00mIjsuTk3CPksX48qAPAJwagD61y-uWj3Srs_-8RNZqJyLZims3x4iEwq_tVbZOMXaFRkdgrmd1joLal3xFCyEkY7HXNBloycaD8HO5KdTU2E8y2jns8uJ0h-GgBNw8f1KXOHDuUHyhnMHDOme-uPccrX1k_Xs_iSf6Q6' 
        : c.avatar;
    });

    const newGroupConvo: Conversation = {
      id: groupConvoId,
      name: groupName,
      isGroup: true,
      avatar: 'group_avatar',
      avatars: groupAvatars,
      unreadCount: 0,
      lastMessage: "You started a group chat with " + selectedContacts.length + " friends.",
      lastMessageTime: 'Just now',
      messages: [
        {
          id: `init_gr_${Date.now()}`,
          senderId: 'system',
          senderName: 'System',
          senderAvatar: 'bell',
          text: `You started a group chat with ${selectedContacts.map(c => c.name).join(', ')}.`,
          timestamp: 'Just now',
          isSelf: false,
          isSystem: true
        }
      ],
      members: ['me', ...selectedContacts.map(c => c.id)]
    };

    setConversations(prev => [newGroupConvo, ...prev]);
    setActiveConversationId(groupConvoId);
    setActiveView('CHAT');
  };

  // 8. Passive updater of chat board history
  const handleUpdateConversationMessages = (convoId: string, updatedMessages: Message[]) => {
    setConversations(prev => 
      prev.map(c => {
        if (c.id === convoId) {
          const lastMsgObj = updatedMessages[updatedMessages.length - 1];
          let updatedMsgText = lastMsgObj?.text || '';
          if (lastMsgObj?.isSystem && lastMsgObj?.isRecalled) {
            updatedMsgText = 'You recalled a message';
          }
          return {
            ...c,
            messages: updatedMessages,
            lastMessage: updatedMsgText,
            lastMessageTime: lastMsgObj?.timestamp || 'Today'
          };
        }
        return c;
      })
    );
  };

  const handleUpdateUser = (updatedUser: Contact) => {
    setCurrentUser(updatedUser);
  };

  // Compute total unread messages count for badges
  const totalUnreadCount = conversations.reduce((sum, c) => sum + c.unreadCount, 0);

  // Switch display cases
  switch (activeView) {
    case 'LOGIN':
      return (
        <div className="w-full h-screen bg-[#fcf9f8] overflow-hidden flex items-center justify-center">
          <LoginRegister onSuccess={handleLoginSuccess} />
        </div>
      );

    case 'CHAT':
      const activeConvo = conversations.find(c => c.id === activeConversationId);
      return activeConvo ? (
        <ChatRoom 
          conversation={activeConvo} 
          user={currentUser}
          onBack={() => {
            setActiveView('MAIN');
            setActiveTab('MESSAGES');
            // Clear unread indicator again for safety
            setConversations(prev => 
              prev.map(c => c.id === activeConversationId ? { ...c, unreadCount: 0 } : c)
            );
          }}
          onUpdateConversationMessages={handleUpdateConversationMessages}
        />
      ) : (
        <div className="flex items-center justify-center h-screen bg-[#EDEDED]">
          <span>Conversation not found. Returning...</span>
          <button onClick={() => setActiveView('MAIN')} className="ml-3 underline bg-[#07c160] text-white px-4 py-2 rounded">Back</button>
        </div>
      );

    case 'PROFILE_DETAILS':
      return (
        <ProfileDetails
          user={currentUser}
          onUpdateUser={handleUpdateUser}
          onBack={() => setActiveView('MAIN')}
        />
      );

    case 'GROUP_CHAT_CREATE':
      return (
        <GroupChatCreate
          contacts={contacts}
          onBack={() => setActiveView('MAIN')}
          onDone={handleGroupChatDone}
        />
      );

    case 'MAIN':
    default:
      return (
        <div className="w-full min-h-screen bg-[#EDEDED] flex flex-col relative select-none">
          {/* Active Tab Screen Panels */}
          <div className="flex-1 overflow-hidden">
            {activeTab === 'MESSAGES' && (
              <MessagesTab 
                conversations={conversations} 
                contacts={contacts}
                onSelectConversation={handleSelectConversation}
                onOpenGroupChatCreate={() => setActiveView('GROUP_CHAT_CREATE')}
                onAddNewContact={handleAddContact}
              />
            )}
            {activeTab === 'CONTACTS' && (
              <ContactsTab
                contacts={contacts}
                onSelectContactChat={handleSelectContactChat}
                onAddContact={handleAddContact}
              />
            )}
            {activeTab === 'ME' && (
              <MeTab
                user={currentUser}
                onLogout={handleLogout}
                onOpenDetails={() => setActiveView('PROFILE_DETAILS')}
              />
            )}
          </div>

          {/* Bottom Persistent Navigation Bar */}
          <BottomNavBar 
            activeTab={activeTab} 
            onChangeTab={setActiveTab} 
            unreadCount={totalUnreadCount}
          />
        </div>
      );
  }
}

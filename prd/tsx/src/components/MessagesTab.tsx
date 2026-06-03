import { useState, useRef, useEffect } from 'react';
import { Plus, MessageSquare, UserPlus, Scan, Bell } from 'lucide-react';
import { Conversation, Contact } from '../types';

interface MessagesTabProps {
  conversations: Conversation[];
  contacts: Contact[];
  onSelectConversation: (convoId: string) => void;
  onOpenGroupChatCreate: () => void;
  onAddNewContact: (newContact: Contact) => void;
}

export default function MessagesTab({ 
  conversations, 
  contacts, 
  onSelectConversation, 
  onOpenGroupChatCreate,
  onAddNewContact
}: MessagesTabProps) {
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const totalUnread = conversations.reduce((acc, conv) => acc + conv.unreadCount, 0);

  // Close dropdown when clicking outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsDropdownOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleStartGroup = () => {
    setIsDropdownOpen(false);
    onOpenGroupChatCreate();
  };

  const handleAddFriend = () => {
    setIsDropdownOpen(false);
    const name = prompt('Enter friend name:');
    if (!name) return;
    const phone = prompt('Enter friend phone number:');
    if (!phone) return;

    const newContact: Contact = {
      id: `contact_custom_${Date.now()}`,
      name,
      phone,
      avatar: 'initials:' + name.substring(0, 2).toUpperCase(),
      region: 'Unknown',
      gender: '男',
      username: 'user_' + Math.floor(Math.random() * 10000),
      signature: 'Hello, I am using ByteIM!',
      ringtone: 'Default'
    };
    onAddNewContact(newContact);
    alert('Friend profile created and added successfully!');
  };

  const handleScan = () => {
    setIsDropdownOpen(false);
    alert('Mock QR scanner initiated. Scanning system active.');
  };

  return (
    <div className="w-full h-full bg-[#EDEDED] pb-[100px]">
      {/* Top Header App Bar */}
      <header className="fixed top-0 left-0 right-0 z-50 h-[56px] bg-white border-b border-[#EEEEEE] flex items-center justify-between px-4">
        <h1 className="font-bold text-[18px] text-[#1c1b1b] tracking-normal">
          Messages{totalUnread > 0 ? `(${totalUnread})` : ''}
        </h1>

        <div className="relative" ref={dropdownRef}>
          <button 
            type="button"
            onClick={() => setIsDropdownOpen(!isDropdownOpen)}
            className="w-10 h-10 flex items-center justify-center rounded-full hover:bg-gray-100 transition-colors active:opacity-75 cursor-pointer"
          >
            <Plus className="w-6 h-6 text-[#006d33]" />
          </button>

          {/* Floating Dropdown popover menu overlay with neat animations */}
          <div 
            className={`absolute right-0 mt-2 w-48 bg-[#313030] text-white rounded-xl shadow-lg z-50 py-2 origin-top-right transition-all duration-200 transform ${
              isDropdownOpen 
                ? 'scale-100 opacity-100 pointer-events-auto' 
                : 'scale-95 opacity-0 pointer-events-none'
            }`}
          >
            <button 
              onClick={handleStartGroup}
              className="w-full flex items-center px-4 py-3 hover:bg-white/10 transition-colors text-left cursor-pointer"
            >
              <MessageSquare className="w-4 h-4 mr-3" />
              <span className="text-[14px]">Start Group Chat</span>
            </button>
            <button 
              onClick={handleAddFriend}
              className="w-full flex items-center px-4 py-3 hover:bg-white/10 transition-colors text-left border-t border-white/5 cursor-pointer"
            >
              <UserPlus className="w-4 h-4 mr-3" />
              <span className="text-[14px]">Add Friend</span>
            </button>
            <button 
              onClick={handleScan}
              className="w-full flex items-center px-4 py-3 hover:bg-white/10 transition-colors text-left border-t border-white/5 cursor-pointer"
            >
              <Scan className="w-4 h-4 mr-3" />
              <span className="text-[14px]">Scan QR Code</span>
            </button>
          </div>
        </div>
      </header>

      {/* Conversations Canvas Screen List */}
      <main className="pt-[56px] px-4 min-h-screen">
        <div className="flex flex-col py-3">
          {conversations.map(conv => {
            const hasUnread = conv.unreadCount > 0;
            const isService = conv.avatar === 'system_bell';

            return (
              <div
                key={conv.id}
                onClick={() => onSelectConversation(conv.id)}
                className="flex items-center h-[72px] px-4 cursor-pointer transition-colors mb-3 rounded-xl bg-white hover:bg-gray-50 active:bg-gray-100 shadow-sm border border-[#EEEEEE]"
              >
                {/* Avatar area */}
                <div className="relative flex-shrink-0">
                  {/* Service notification style */}
                  {isService ? (
                    <div className="relative w-[50px] h-[50px] rounded-xl bg-[#006d33] flex items-center justify-center">
                      <Bell className="w-6 h-6 text-white" />
                    </div>
                  ) : conv.isGroup ? (
                    /* Group Avatars Composite Grid representation */
                    <div className="relative grid grid-cols-2 gap-0.5 w-[50px] h-[50px] rounded-xl overflow-hidden bg-gray-100 p-0.5 border border-gray-200">
                      {(conv.avatars || []).slice(0, 3).map((av, index) => (
                        <img 
                          key={index} 
                          className="w-full h-full object-cover rounded-sm" 
                          alt="gv" 
                          src={av}
                        />
                      ))}
                      <div className="bg-gray-200 flex items-center justify-center text-[9px] font-bold text-gray-500 rounded-sm">
                        9+
                      </div>
                    </div>
                  ) : (
                    /* Standard Profile Face */
                    <img 
                      className="w-[50px] h-[50px] rounded-xl object-cover bg-gray-50 border border-gray-100" 
                      alt="avatar" 
                      src={conv.avatar}
                    />
                  )}

                  {/* Red Badge Indicator */}
                  {hasUnread && (
                    <div className="absolute -top-1.5 -right-1.5 bg-[#FA5151] text-white min-w-[18px] h-[18px] px-1 flex items-center justify-center rounded-full text-[10px] font-bold border-2 border-white">
                      {conv.unreadCount}
                    </div>
                  )}
                </div>

                {/* Body metadata list details */}
                <div className="ml-4 flex-grow flex flex-col justify-center overflow-hidden">
                  <div className="flex justify-between items-baseline">
                    <span className="font-semibold text-[16px] text-[#1c1b1b] truncate">
                      {conv.name}
                    </span>
                    <span className="text-[11px] text-[#808080] tracking-tight ml-1 font-sans">
                      {conv.lastMessageTime}
                    </span>
                  </div>

                  <div className="flex items-center mt-0.5">
                    {/* Mentions / Highlights styled inside status error red */}
                    {conv.isMentionedMe && (
                      <span className="text-[13px] font-semibold text-[#FA5151] mr-1 whitespace-nowrap">
                        [@me]
                      </span>
                    )}
                    <span className="text-[14px] text-[#808080] truncate w-full">
                      {conv.lastMessage}
                    </span>
                  </div>
                </div>
              </div>
            );
          })}

          {conversations.length === 0 && (
            <div className="py-24 text-center text-gray-400">
              No active discussion threads. Start one by clicking + icon.
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

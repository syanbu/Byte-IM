import React, { useState, useRef, useEffect } from 'react';
import { 
  ArrowLeft, 
  MoreHorizontal, 
  Mic, 
  Smile, 
  PlusCircle, 
  Check, 
  Copy, 
  Undo2, 
  Trash2,
  Send,
  Loader2
} from 'lucide-react';
import { Conversation, Message, Contact } from '../types';
import { getAutoReply } from '../data';

interface ChatRoomProps {
  conversation: Conversation;
  user: Contact;
  onBack: () => void;
  onUpdateConversationMessages: (convoId: string, updatedMessages: Message[]) => void;
}

export default function ChatRoom({ 
  conversation, 
  user, 
  onBack, 
  onUpdateConversationMessages 
}: ChatRoomProps) {
  const [inputText, setInputText] = useState('');
  const [activeMenuMessageId, setActiveMenuMessageId] = useState<string | null>(null);
  const [peerIsTyping, setPeerIsTyping] = useState(false);
  
  const chatThreadRef = useRef<HTMLDivElement>(null);

  // Scroll to bottom whenever messages or typing state changes
  const scrollToBottom = () => {
    if (chatThreadRef.current) {
      chatThreadRef.current.scrollTop = chatThreadRef.current.scrollHeight;
    }
  };

  useEffect(() => {
    scrollToBottom();
  }, [conversation.messages, peerIsTyping]);

  useEffect(() => {
    // Scroll initially
    const timer = setTimeout(scrollToBottom, 50);
    return () => clearTimeout(timer);
  }, [conversation.id]);

  const handleSendMessage = () => {
    if (!inputText.trim()) return;

    const cleanText = inputText.trim();
    const now = new Date();
    const timeString = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;

    const newMsg: Message = {
      id: `msg_${Date.now()}`,
      senderId: 'me',
      senderName: user.name,
      senderAvatar: user.avatar,
      text: cleanText,
      timestamp: timeString,
      isSelf: true,
      status: 'sending'
    };

    const currentMsgHistory = [...conversation.messages, newMsg];
    onUpdateConversationMessages(conversation.id, currentMsgHistory);
    setInputText('');

    // Trigger visual "sent" transition immediately
    setTimeout(() => {
      const updated = currentMsgHistory.map(m => 
        m.id === newMsg.id ? { ...m, status: 'sent' as const } : m
      );
      onUpdateConversationMessages(conversation.id, updated);
    }, 400);

    // Trigger visual "read" and autotyping indicators
    setTimeout(() => {
      const updated = currentMsgHistory.map(m => 
        m.id === newMsg.id ? { ...m, status: 'read' as const } : m
      );
      onUpdateConversationMessages(conversation.id, updated);
      
      // Auto reply from a peer, except for systemUpdates thread
      if (conversation.id !== 'system_updates') {
        setPeerIsTyping(true);
        setTimeout(() => {
          setPeerIsTyping(false);
          const replyText = getAutoReply(conversation.id, cleanText);
          const replyMsg: Message = {
            id: `msg_reply_${Date.now()}`,
            senderId: conversation.id,
            senderName: conversation.name,
            senderAvatar: conversation.avatar,
            text: replyText,
            timestamp: `${String(new Date().getHours()).padStart(2, '0')}:${String(new Date().getMinutes()).padStart(2, '0')}`,
            isSelf: false
          };
          onUpdateConversationMessages(conversation.id, [...updated, replyMsg]);
        }, 1500);
      }
    }, 1000);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  const handleLeftPressOrContextMenu = (e: React.MouseEvent, messageId: string) => {
    e.preventDefault();
    if (activeMenuMessageId === messageId) {
      setActiveMenuMessageId(null);
    } else {
      setActiveMenuMessageId(messageId);
    }
  };

  const handleCopyMessage = (text: string) => {
    navigator.clipboard.writeText(text);
    setActiveMenuMessageId(null);
    alert('Message copied to clipboard!');
  };

  const handleRecallMessage = (messageId: string) => {
    const updated = conversation.messages.map(m => {
      if (m.id === messageId) {
        return {
          ...m,
          isSystem: true,
          isRecalled: true,
          text: 'You recalled a message'
        };
      }
      return m;
    });
    onUpdateConversationMessages(conversation.id, updated);
    setActiveMenuMessageId(null);
  };

  return (
    <div className="w-full h-full bg-[#EDEDED] flex flex-col relative overflow-hidden">
      {/* FIXED Top App Bar */}
      <header className="fixed top-0 left-0 right-0 z-50 h-[56px] bg-white flex items-center justify-between px-4 border-b border-[#EEEEEE]">
        <button 
          onClick={onBack}
          className="flex items-center gap-1 active:opacity-75 cursor-pointer text-[#07c160] font-sans text-[15px]"
        >
          <ArrowLeft className="w-6 h-6 stroke-[2.5]" />
          <span>返回</span>
        </button>

        <div className="flex flex-col items-center">
          <h1 className="font-semibold text-[17px] text-[#1c1b1b] tracking-tight">
            {conversation.name}
          </h1>
          {conversation.isGroup && (
            <span className="text-[10px] text-[#808080] font-sans -mt-0.5">
              4 members
            </span>
          )}
        </div>

        <button 
          onClick={() => alert(`Details/Sync settings modal for thread: ${conversation.name}`)}
          className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-gray-100 cursor-pointer active:opacity-75"
        >
          <MoreHorizontal className="w-5 h-5 text-[#3d4a3d]" />
        </button>
      </header>

      {/* Scrollable Message Area */}
      <main 
        ref={chatThreadRef}
        className="flex-1 overflow-y-auto pt-[56px] pb-[72px] px-4 flex flex-col gap-4 font-sans"
      >
        <div className="h-4 flex-shrink-0" />

        {/* Dynamic dates placeholder */}
        <div className="flex justify-center my-1">
          <span className="bg-[#f0eded]/60 text-[#808080] font-normal text-[12px] px-3 py-1 rounded-full">
            Yesterday 14:30
          </span>
        </div>

        {/* Message elements renderer */}
        {conversation.messages.map((msg) => {
          if (msg.isSystem) {
            return (
              <div key={msg.id} className="flex justify-center my-1 select-none animate-fade-in">
                <span className="text-[#808080] text-[12px] bg-[#f0eded]/50 px-4 py-1 rounded-full">
                  {msg.text}
                </span>
              </div>
            );
          }

          const hasMenuOpen = activeMenuMessageId === msg.id;
          const avatarSrc = msg.senderAvatar.startsWith('initials:') ? '' : msg.senderAvatar;

          return (
            <div 
              key={msg.id}
              className={`flex items-start gap-3 w-full relative group transition-all duration-300 ${
                msg.isSelf ? 'justify-end' : 'justify-start'
              }`}
            >
              {/* Mentions / Recall overlay context dialog element */}
              {hasMenuOpen && (
                <div 
                  className={`absolute -top-12 z-[60] bg-[#313030] text-white px-1 py-1 rounded-lg flex items-center shadow-lg transition-all border border-gray-700 ${
                    msg.isSelf ? 'right-2' : 'left-12'
                  }`}
                >
                  <button 
                    onClick={() => handleCopyMessage(msg.text)}
                    className="px-3 py-1 text-xs font-semibold hover:bg-white/10 rounded flex items-center gap-1.5 cursor-pointer"
                  >
                    <Copy className="w-3.5 h-3.5" />
                    <span>Copy</span>
                  </button>
                  {msg.isSelf && (
                    <>
                      <div className="w-[1px] h-4 bg-white/20 mx-1" />
                      <button 
                        onClick={() => handleRecallMessage(msg.id)}
                        className="px-3 py-1 text-xs font-semibold hover:bg-white/10 rounded text-[#ff8475] flex items-center gap-1.5 cursor-pointer"
                      >
                        <Undo2 className="w-3.5 h-3.5" />
                        <span>Recall</span>
                      </button>
                    </>
                  )}
                </div>
              )}

              {/* Peer Profile Avatar Image on the left */}
              {!msg.isSelf && (
                <div className="w-10 h-10 rounded-lg overflow-hidden flex-shrink-0 bg-gray-200 border border-gray-100 select-none">
                  {avatarSrc ? (
                    <img 
                      alt="Avatar" 
                      className="w-full h-full object-cover" 
                      src={avatarSrc}
                    />
                  ) : (
                    <div className="w-full h-full bg-[#07c160] flex items-center justify-center text-white font-bold text-[14px]">
                      {msg.senderName.substring(0, 2).toUpperCase()}
                    </div>
                  )}
                </div>
              )}

              {/* Message Bubble content wrapper */}
              <div 
                className={`flex flex-col max-w-[75%] gap-0.5 ${
                  msg.isSelf ? 'items-end' : 'items-start'
                }`}
              >
                {/* Username on top of bubble inside group conversation */}
                {!msg.isSelf && conversation.isGroup && (
                  <span className="text-[11px] text-[#808080] ml-1 select-none font-sans">
                    {msg.senderName}
                  </span>
                )}

                {/* Actual Bubble Box with tail metaphor */}
                <div 
                  onClick={(e) => handleLeftPressOrContextMenu(e, msg.id)}
                  className={`relative px-3 py-2 rounded-xl text-[15px] font-sans shadow-sm cursor-pointer select-text border transition-all duration-200 ${
                    msg.isSelf 
                      ? 'bg-[#95EC69] text-[#1c1b1b] rounded-tr-none border-[#bbcbba] hover:scale-[1.02]' 
                      : 'bg-white text-[#1c1b1b] rounded-tl-none border-gray-100 hover:scale-[1.02]'
                  } ${
                    hasMenuOpen ? 'ring-2 ring-[#07c160]/40 ring-offset-1' : ''
                  }`}
                >
                  {/* Highlight mentions of @Admin or @me or custom terms */}
                  {msg.text.includes('@Admin') || msg.text.includes('@me') ? (
                    <p className="leading-relaxed">
                      {msg.text.split(/(@Admin|@me)/g).map((part, i) => {
                        if (part === '@Admin' || part === '@me') {
                          return (
                            <span key={i} className="text-[#006d33] font-bold hover:underline cursor-pointer">
                              {part}
                            </span>
                          );
                        }
                        return part;
                      })}
                    </p>
                  ) : (
                    <p className="leading-relaxed">{msg.text}</p>
                  )}
                </div>

                {/* Sub status row for self: state reads, clocks */}
                <div className="flex items-center gap-1 select-none mt-0.5">
                  <span className="text-[10px] text-[#808080] tracking-tight">
                    {msg.timestamp}
                  </span>
                  {msg.isSelf && (
                    <span className="text-[#07c160] flex items-center">
                      {msg.status === 'sending' && (
                        <Loader2 className="w-3 h-3 animate-spin text-gray-500" />
                      )}
                      {msg.status === 'sent' && (
                        <Check className="w-3.5 h-3.5 text-gray-400 stroke-[3.5]" />
                      )}
                      {msg.status === 'read' && (
                        <div className="flex items-center text-primary">
                          <Check className="w-3 h-3 stroke-[3.5]" />
                          <Check className="w-3 h-3 -ml-1.5 stroke-[3.5]" />
                        </div>
                      )}
                    </span>
                  )}
                </div>
              </div>
            </div>
          );
        })}

        {/* Dynamic typing indicator view */}
        {peerIsTyping && (
          <div className="flex items-start gap-3 w-full justify-start animate-pulse">
            <div className="w-10 h-10 rounded-lg overflow-hidden flex-shrink-0 bg-gray-200 border border-gray-100">
              <img 
                alt="Avatar" 
                className="w-full h-full object-cover" 
                src={conversation.avatar}
              />
            </div>
            <div className="flex flex-col gap-0.5 items-start">
              <span className="relative bg-white text-gray-500 px-4 py-2 rounded-xl rounded-tl-none border border-gray-100 shadow-sm flex items-center gap-1 text-[14px]">
                <span className="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                <span className="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                <span className="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
              </span>
            </div>
          </div>
        )}
      </main>

      {/* COMPOSER Bottom input bar */}
      <footer className="fixed bottom-0 left-0 right-0 bg-white border-t border-[#EEEEEE] px-3 py-2 flex items-end gap-3 min-h-[56px] z-50">
        <button 
          onClick={() => alert('Vocal voice messages note recorder (ByteIM MIC)')}
          className="mb-1 p-2 hover:bg-gray-100 rounded-full transition-colors flex items-center justify-center cursor-pointer active:scale-95"
          type="button"
        >
          <Mic className="w-5 h-5 text-gray-500" />
        </button>

        <div className="flex-1 bg-gray-100 rounded-lg min-h-[40px] px-3 py-1 flex items-center">
          <textarea
            className="w-full bg-transparent border-none focus:ring-0 p-0 text-[15px] resize-none outline-none text-[#1c1b1b] placeholder:text-gray-400 font-sans leading-relaxed"
            placeholder="Type a message..."
            rows={1}
            value={inputText}
            onKeyDown={handleKeyDown}
            onChange={(e) => setInputText(e.target.value)}
            style={{ maxHeight: '120px' }}
          />
        </div>

        <button 
          onClick={() => alert('Emoticons & smileys panel')}
          className="mb-1 p-2 hover:bg-gray-100 rounded-full transition-colors flex items-center justify-center cursor-pointer active:scale-95 animate-fade-in"
          type="button"
        >
          <Smile className="w-5 h-5 text-gray-500" />
        </button>

        <div className="mb-1">
          {inputText.trim().length > 0 ? (
            <button
              onClick={handleSendMessage}
              className="bg-[#07c160] text-white px-4 py-1.5 rounded-lg font-semibold text-xs active:scale-95 hover:bg-opacity-90 transition-transform cursor-pointer"
              type="button"
            >
              <span>Send</span>
            </button>
          ) : (
            <button
              onClick={() => alert('Attachments panel: Images, Files, Locations, or Stickers.')}
              className="p-2 hover:bg-gray-100 rounded-full transition-colors flex items-center justify-center cursor-pointer active:scale-95 animate-fade-in"
              type="button"
            >
              <PlusCircle className="w-5 h-5 text-[#07c160]" />
            </button>
          )}
        </div>
      </footer>
    </div>
  );
}

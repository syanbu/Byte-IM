export interface Contact {
  id: string;
  name: string;
  phone: string;
  avatar: string;
  region: string;
  gender: string;
  username: string;
  signature: string;
  ringtone: string;
  myAddress?: string;
  invoiceTitle?: string;
}

export interface Message {
  id: string;
  senderId: string;
  senderName: string;
  senderAvatar: string;
  text: string;
  timestamp: string; // e.g. "14:30"
  isSelf: boolean;
  isSystem?: boolean;
  isRecalled?: boolean;
  isMentioned?: boolean;
  status?: 'sending' | 'sent' | 'read';
}

export interface Conversation {
  id: string;
  name: string;
  isGroup: boolean;
  avatar: string;
  avatars?: string[]; // Multiple avatars for group
  unreadCount: number;
  lastMessage: string;
  lastMessageTime: string;
  messages: Message[];
  members: string[]; // Contact IDs
  isMentionedMe?: boolean;
}

export type ActiveView = 
  | 'LOGIN'
  | 'MAIN'
  | 'CHAT'
  | 'PROFILE_DETAILS'
  | 'GROUP_CHAT_CREATE';

export type ActiveTab = 
  | 'MESSAGES'
  | 'CONTACTS'
  | 'ME';

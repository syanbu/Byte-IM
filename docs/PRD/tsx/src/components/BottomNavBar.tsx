import { MessageSquare, Users, User } from 'lucide-react';
import { ActiveTab } from '../types';

interface BottomNavBarProps {
  activeTab: ActiveTab;
  onChangeTab: (tab: ActiveTab) => void;
  unreadCount?: number;
}

export default function BottomNavBar({ activeTab, onChangeTab, unreadCount = 0 }: BottomNavBarProps) {
  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 h-[56px] bg-white border-t border-[#EEEEEE] flex justify-around items-center px-2 select-none shadow">
      {/* Messages Tab */}
      <button 
        type="button"
        onClick={() => onChangeTab('MESSAGES')}
        className={`relative flex flex-col items-center justify-center hover:bg-gray-50 active:scale-95 transition-transform duration-150 flex-1 h-full cursor-pointer ${
          activeTab === 'MESSAGES' ? 'text-[#07c160]' : 'text-[#808080]'
        }`}
      >
        <MessageSquare className={`w-5 h-5 ${activeTab === 'MESSAGES' ? 'fill-current' : ''}`} />
        <span className="text-[11px] font-semibold mt-0.5 tracking-wider uppercase font-sans">
          Messages
        </span>

        {/* Dynamic unread overlay count */}
        {unreadCount > 0 && (
          <div className="absolute top-1 right-1/4 bg-[#FA5151] text-white min-w-[15px] h-[15px] px-1 flex items-center justify-center rounded-full text-[9px] font-bold">
            {unreadCount}
          </div>
        )}
      </button>

      {/* Contacts Tab */}
      <button 
        type="button"
        onClick={() => onChangeTab('CONTACTS')}
        className={`flex flex-col items-center justify-center hover:bg-gray-50 active:scale-95 transition-transform duration-150 flex-1 h-full cursor-pointer ${
          activeTab === 'CONTACTS' ? 'text-[#07c160]' : 'text-[#808080]'
        }`}
      >
        <Users className={`w-5 h-5 ${activeTab === 'CONTACTS' ? 'fill-current' : ''}`} />
        <span className="text-[11px] font-semibold mt-0.5 tracking-wider uppercase font-sans">
          Contacts
        </span>
      </button>

      {/* Me Tab */}
      <button 
        type="button"
        onClick={() => onChangeTab('ME')}
        className={`flex flex-col items-center justify-center hover:bg-gray-50 active:scale-95 transition-transform duration-150 flex-1 h-full cursor-pointer ${
          activeTab === 'ME' ? 'text-[#07c160]' : 'text-[#808080]'
        }`}
      >
        <User className={`w-5 h-5 ${activeTab === 'ME' ? 'fill-current' : ''}`} />
        <span className="text-[11px] font-semibold mt-0.5 tracking-wider uppercase font-sans">
          Me
        </span>
      </button>
    </nav>
  );
}

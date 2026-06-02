import { Contact } from '../types';
import { 
  CreditCard, 
  Bookmark, 
  Image as ImageIcon, 
  Folder, 
  Smile, 
  Settings as SettingsIcon,
  ChevronRight,
  QrCode
} from 'lucide-react';

interface MeTabProps {
  user: Contact;
  onLogout: () => void;
  onOpenDetails: () => void;
}

export default function MeTab({ user, onLogout, onOpenDetails }: MeTabProps) {
  const avatarSrc = user.avatar.startsWith('initials:') 
    ? '' 
    : user.avatar;

  return (
    <div className="w-full min-h-screen bg-[#EDEDED] pb-[100px]">
      {/* Top Header Placeholder to align cleanly inside fixed navigation container */}
      <div className="h-4 bg-[#EDEDED]" />

      {/* Profile Banner */}
      <section 
        onClick={onOpenDetails}
        className="bg-white active:bg-gray-100 transition-colors cursor-pointer px-4 py-6 flex items-center justify-between border-b border-[#EEEEEE] shadow-sm"
      >
        <div className="flex items-center gap-4">
          <div className="relative w-[64px] h-[64px] flex-shrink-0">
            {avatarSrc ? (
              <img 
                alt="Profile Avatar" 
                className="w-full h-full rounded-xl object-cover" 
                src={avatarSrc}
              />
            ) : (
              <div className="w-full h-full rounded-xl bg-[#07c160] flex items-center justify-center text-white font-bold text-xl">
                {user.name.substring(0, 2).toUpperCase()}
              </div>
            )}
          </div>
          <div className="flex flex-col">
            <span className="font-semibold text-[17px] text-[#1c1b1b]">{user.name}</span>
            <span className="text-[13px] text-[#808080] mt-0.5">ID: {user.phone}</span>
          </div>
        </div>
        <div className="flex items-center gap-1.5 text-[#6c7b6c]">
          <QrCode className="w-5 h-5 text-gray-400" />
          <ChevronRight className="w-5 h-5 text-gray-300" />
        </div>
      </section>

      {/* Menu Options Group 1 */}
      <div className="mt-2 space-y-2">
        <div className="bg-white border-y border-[#EEEEEE]">
          <button 
            type="button"
            onClick={() => alert('Pay services and wallet transaction history requested (ByteIM Wallet v1.0)')}
            className="w-full h-[56px] px-4 flex items-center justify-between hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <div className="flex items-center gap-4">
              <CreditCard className="w-5 h-5 text-[#07c160]" />
              <span className="text-[16px] text-[#1c1b1b] font-medium">Services</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-300" />
          </button>
        </div>

        {/* Menu Options Group 2 */}
        <div className="bg-white border-y border-[#EEEEEE] divide-y divide-[#EEEEEE]">
          <button 
            type="button"
            onClick={() => alert('Favorites content repository (Bookmarked chats/files saved securely)')}
            className="w-full h-[56px] px-4 flex items-center justify-between hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <div className="flex items-center gap-4">
              <Bookmark className="w-5 h-5 text-[#07c160]" />
              <span className="text-[16px] text-[#1c1b1b] font-medium">Favorites</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-300" />
          </button>

          <button 
            type="button"
            onClick={() => alert('Moments Social Feed: Sharing is private and entirely yours.')}
            className="w-full h-[56px] px-4 flex items-center justify-between hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <div className="flex items-center gap-4">
              <ImageIcon className="w-5 h-5 text-[#07c160]" />
              <span className="text-[16px] text-[#1c1b1b] font-medium">Moments</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-300" />
          </button>

          <button 
            type="button"
            onClick={() => alert('Files Cabinet - Documents shared and stored locally')}
            className="w-full h-[56px] px-4 flex items-center justify-between hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <div className="flex items-center gap-4">
              <Folder className="w-5 h-5 text-[#07c160]" />
              <span className="text-[16px] text-[#1c1b1b] font-medium">Files</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-300" />
          </button>

          <button 
            type="button"
            onClick={() => alert('Stickers and Custom Emoticons Shop')}
            className="w-full h-[56px] px-4 flex items-center justify-between hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <div className="flex items-center gap-4">
              <Smile className="w-5 h-5 text-[#07c160]" />
              <span className="text-[16px] text-[#1c1b1b] font-medium">Stickers</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-300" />
          </button>
        </div>

        {/* Menu Options Group 3 */}
        <div className="bg-white border-y border-[#EEEEEE]">
          <button 
            type="button"
            onClick={() => alert('System Settings, Network Diagnostics, and Safe Backups.')}
            className="w-full h-[56px] px-4 flex items-center justify-between hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <div className="flex items-center gap-4">
              <SettingsIcon className="w-5 h-5 text-[#07c160]" />
              <span className="text-[16px] text-[#1c1b1b] font-medium">Settings</span>
            </div>
            <ChevronRight className="w-5 h-5 text-gray-300" />
          </button>
        </div>

        {/* Logout Section Button */}
        <div className="mt-8 px-4">
          <button 
            type="button"
            onClick={onLogout}
            className="w-full h-[50px] bg-white text-[#ba1a1a] font-medium text-[16px] rounded-xl active:opacity-70 active:bg-gray-100 transition-all duration-150 border border-[#EEEEEE] shadow-sm cursor-pointer"
          >
            Logout
          </button>
        </div>

        {/* Version Footnote */}
        <div className="py-8 flex flex-col items-center justify-center gap-1 opacity-40">
          <span className="text-xs font-semibold text-[#808080]">ByteIM v2.4.1</span>
          <span className="text-[11px] text-[#808080]">Powered by Open Protocols</span>
        </div>
      </div>
    </div>
  );
}

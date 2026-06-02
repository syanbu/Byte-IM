import { useState } from 'react';
import { ChevronRight, ArrowLeft, QrCode, Edit3, Check } from 'lucide-react';
import { Contact } from '../types';

interface ProfileDetailsProps {
  user: Contact;
  onUpdateUser: (updatedUser: Contact) => void;
  onBack: () => void;
}

export default function ProfileDetails({ user, onUpdateUser, onBack }: ProfileDetailsProps) {
  const [editingField, setEditingField] = useState<string | null>(null);
  const [fieldValue, setFieldValue] = useState('');

  const handleEditClick = (fieldName: string, currentVal: string) => {
    setEditingField(fieldName);
    setFieldValue(currentVal || '');
  };

  const handleSaveField = (fieldName: keyof Contact) => {
    onUpdateUser({
      ...user,
      [fieldName]: fieldValue
    });
    setEditingField(null);
  };

  const avatarSrc = user.avatar.startsWith('initials:') 
    ? '' 
    : user.avatar;

  return (
    <div className="w-full h-full bg-[#EDEDED] flex flex-col relative overflow-y-auto">
      {/* TopAppBar header */}
      <header className="bg-[#EDEDED] fixed top-0 left-0 right-0 z-50 h-[56px] flex items-center justify-between px-4">
        <button 
          onClick={onBack}
          className="flex items-center justify-center w-8 h-8 -ml-2 text-[#1c1b1b] active:opacity-70 cursor-pointer"
        >
          <ArrowLeft className="w-6 h-6" />
        </button>
        <h1 className="font-semibold text-[18px] text-[#1c1b1b] absolute left-1/2 -translate-x-1/2">
          个人资料
        </h1>
        <div className="w-8"></div>
      </header>

      {/* Main Panel Canvas */}
      <div className="pt-[56px] pb-8 flex flex-col">
        {/* Helper edit bar */}
        {editingField && (
          <div className="bg-[#07c160]/10 text-[#006d33] px-4 py-2 text-xs flex items-center justify-between border-b border-[#07c160]/30 animate-fade-in">
            <div className="flex items-center gap-2">
              <Edit3 className="w-3.5 h-3.5 animate-bounce" />
              <span>Editing field <b>{editingField}</b>:</span>
            </div>
            <div className="flex items-center gap-3">
              <input 
                type="text" 
                value={fieldValue} 
                onChange={(e) => setFieldValue(e.target.value)}
                className="bg-white border text-xs px-2 py-0.5 rounded outline-none text-[#1c1b1b] w-40 focus:border-[#07c160]"
              />
              <button 
                onClick={() => handleSaveField(editingField as keyof Contact)}
                className="bg-[#07c160] text-white p-1 rounded hover:bg-[#006d33]"
              >
                <Check className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        )}

        {/* Group 1: Basic Info */}
        <section className="bg-white flex flex-col w-full border-t border-b border-[#EEEEEE] mt-2">
          {/* Avatar Row */}
          <div 
            onClick={() => handleEditClick('avatar', user.avatar)} 
            className="flex items-center justify-between px-4 py-3 border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">头像</span>
            <div className="flex items-center gap-1">
              {avatarSrc ? (
                <img 
                  alt="Avatar" 
                  className="w-12 h-12 rounded object-cover border border-[#EEEEEE]" 
                  src={avatarSrc}
                />
              ) : (
                <div className="w-12 h-12 rounded bg-[#07c160] flex items-center justify-center text-white font-bold text-lg">
                  {user.name.substring(0, 2).toUpperCase()}
                </div>
              )}
              <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
            </div>
          </div>

          {/* Name Row */}
          <div 
            onClick={() => handleEditClick('name', user.name)}
            className="flex items-center justify-between px-4 h-[56px] border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">名字</span>
            <div className="flex items-center gap-1">
              <span className="text-[14px] text-[#808080]">{user.name}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
            </div>
          </div>

          {/* Gender Row */}
          <div 
            onClick={() => handleEditClick('gender', user.gender)}
            className="flex items-center justify-between px-4 h-[56px] border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">性别</span>
            <div className="flex items-center gap-1">
              <span className="text-[14px] text-[#808080]">{user.gender}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
            </div>
          </div>

          {/* Region Row */}
          <div 
            onClick={() => handleEditClick('region', user.region)}
            className="flex items-center justify-between px-4 h-[56px] border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">地区</span>
            <div className="flex items-center gap-1">
              <span className="text-[14px] text-[#808080]">{user.region}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
            </div>
          </div>

          {/* Phone Row */}
          <div 
            onClick={() => handleEditClick('phone', user.phone)}
            className="flex items-center justify-between px-4 h-[56px] border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">手机号</span>
            <div className="flex items-center gap-1">
              <span className="text-[14px] text-[#808080]">{user.phone}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
            </div>
          </div>

          {/* ByteIM ID Row */}
          <div 
            onClick={() => handleEditClick('username', user.username)}
            className="flex items-center justify-between px-4 h-[56px] border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">微信号</span>
            <div className="flex items-center gap-1">
              <span className="text-[14px] text-[#808080]">{user.username}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
            </div>
          </div>

          {/* QR Code Row */}
          <div 
            className="flex items-center justify-between px-4 h-[56px] border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">我的二维码</span>
            <div className="flex items-center gap-1">
              <QrCode className="w-5 h-5 text-[#808080]" />
              <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
            </div>
          </div>

          {/* Tickle Row */}
          <div 
            onClick={() => alert(`拍了拍 ${user.name} 的肩膀`)}
            className="flex items-center justify-between px-4 h-[56px] border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">拍一拍</span>
            <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
          </div>

          {/* Signature Row */}
          <div 
            onClick={() => handleEditClick('signature', user.signature)}
            className="flex items-center justify-between px-4 h-[56px] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">签名</span>
            <div className="flex items-center gap-1 overflow-hidden max-w-[240px]">
              <span className="text-[14px] text-[#808080] truncate">{user.signature}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7] flex-shrink-0" />
            </div>
          </div>
        </section>

        {/* Group 2: Settings */}
        <section className="bg-white flex flex-col w-full border-t border-b border-[#EEEEEE] mt-2">
          {/* Ringtone Row */}
          <div 
            onClick={() => handleEditClick('ringtone', user.ringtone)}
            className="flex items-center justify-between px-4 h-[56px] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">来电铃声</span>
            <div className="flex items-center gap-1">
              <span className="text-[14px] text-[#808080]">{user.ringtone}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7]" />
            </div>
          </div>
        </section>

        {/* Group 3: Utility */}
        <section className="bg-white flex flex-col w-full border-t border-b border-[#EEEEEE] mt-2">
          {/* Address Row */}
          <div 
            onClick={() => handleEditClick('myAddress', user.myAddress || '')}
            className="flex items-center justify-between px-4 h-[56px] border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">我的地址</span>
            <div className="flex items-center gap-1 max-w-[240px]">
              <span className="text-[14px] text-[#808080] truncate">{user.myAddress || '未设定'}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7] flex-shrink-0" />
            </div>
          </div>

          {/* Invoice Title Row */}
          <div 
            onClick={() => handleEditClick('invoiceTitle', user.invoiceTitle || '')}
            className="flex items-center justify-between px-4 h-[56px] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
          >
            <span className="font-medium text-[16px] text-[#1c1b1b]">我的发票抬头</span>
            <div className="flex items-center gap-1 max-w-[240px]">
              <span className="text-[14px] text-[#808080] truncate">{user.invoiceTitle || '未设定'}</span>
              <ChevronRight className="w-5 h-5 text-[#c6c6c7] flex-shrink-0" />
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}

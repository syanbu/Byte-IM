import { useState } from 'react';
import { Search, Plus, UserPlus, Menu } from 'lucide-react';
import { Contact } from '../types';

interface ContactsTabProps {
  contacts: Contact[];
  onSelectContactChat: (contactId: string) => void;
  onAddContact: (newContact: Contact) => void;
}

export default function ContactsTab({ contacts, onSelectContactChat, onAddContact }: ContactsTabProps) {
  const [searchTerm, setSearchTerm] = useState('');
  const [isFocused, setIsFocused] = useState(false);

  // Filter out ME
  const actualContacts = contacts.filter(c => c.id !== 'me');

  // Perform search queries
  const filteredContacts = actualContacts.filter(c => 
    c.name.toLowerCase().includes(searchTerm.toLowerCase()) || 
    c.phone.includes(searchTerm) || 
    (c.username && c.username.toLowerCase().includes(searchTerm.toLowerCase()))
  );

  const getLetterGroup = (contact: Contact): string => {
    const firstChar = contact.name.trim().charAt(0).toUpperCase();
    if (/^[A-Z]$/.test(firstChar)) return firstChar;
    if (contact.name.startsWith('阿') || contact.name.startsWith('爱') || contact.name.startsWith('艾') || contact.name.toLowerCase().startsWith('aa') || contact.name.toLowerCase().startsWith('ak')) {
      return 'A'; 
    }
    if (contact.name.startsWith('北')) return 'B';
    return '#';
  };

  // Grouped contacts mapped to letters
  const alphabet = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '#'];

  const grouped: { [letter: string]: Contact[] } = {};
  filteredContacts.forEach(c => {
    const group = getLetterGroup(c);
    if (!grouped[group]) grouped[group] = [];
    grouped[group].push(c);
  });

  // Sort contacts in each group alphabetically
  Object.keys(grouped).forEach(k => {
    grouped[k].sort((a, b) => a.name.localeCompare(b.name));
  });

  const handleAddNewContactPrompt = () => {
    const name = prompt('Enter contact name:');
    if (!name) return;
    const phone = prompt('Enter contact phone number:');
    if (!phone) return;

    const newContact: Contact = {
      id: `contact_custom_${Date.now()}`,
      name,
      phone,
      avatar: 'initials:' + name.substring(0, 2).toUpperCase(),
      region: 'Unknown Region',
      gender: '男',
      username: 'user_' + Math.floor(Math.random() * 10000),
      signature: 'Hello, I am using ByteIM!',
      ringtone: 'Default'
    };
    onAddContact(newContact);
    alert('Friend added successfully!');
  };

  return (
    <div className="w-full h-full bg-[#fcf9f8] pb-[100px]">
      {/* Target header top bar */}
      <header className="fixed top-0 left-0 right-0 z-50 h-[56px] bg-white border-b border-[#EEEEEE] flex items-center justify-between px-4">
        <div className="flex items-center gap-4">
          <Menu className="w-6 h-6 text-[#1c1b1b]" />
          <h1 className="font-semibold text-[18px] text-[#1c1b1b]">Contacts</h1>
        </div>
        <div className="flex items-center gap-2">
          <button 
            type="button"
            onClick={handleAddNewContactPrompt}
            className="flex items-center justify-center p-1 rounded-full text-[#1c1b1b] hover:bg-gray-100 active:opacity-70 transition-all cursor-pointer"
          >
            <UserPlus className="w-6 h-6 text-primary" />
          </button>
        </div>
      </header>

      {/* Main contacts lists viewport */}
      <div className="pt-[56px]">
        {/* Search Input Box */}
        <div className="px-4 py-3 bg-white">
          <div 
            className={`flex items-center rounded-xl px-4 py-2 gap-3 transition-all duration-300 ${
              isFocused 
                ? 'bg-white shadow-sm ring-2 ring-[#07c160]' 
                : 'bg-gray-100'
            }`}
          >
            <Search className="w-5 h-5 text-gray-400" />
            <input
              type="text"
              className="bg-transparent border-none outline-none focus:ring-0 w-full text-[14px] placeholder:text-gray-400 text-[#1c1b1b]"
              placeholder="Search contacts..."
              value={searchTerm}
              onFocus={() => setIsFocused(true)}
              onBlur={() => setIsFocused(false)}
              onChange={(e) => setSearchTerm(e.target.value)}
            />
          </div>
        </div>

        {/* Categories Scroller list content */}
        <div>
          {alphabet.map(letter => {
            const groupList = grouped[letter] || [];
            if (groupList.length === 0) return null;

            return (
              <div key={letter} className="w-full">
                {/* Section Tag */}
                <div className="bg-[#f6f3f2] px-4 py-1.5 flex items-center">
                  <span className="text-[12px] font-semibold text-gray-400 uppercase tracking-wider font-sans">
                    {letter}
                  </span>
                </div>

                {/* Contacts Rows in Group */}
                <div className="bg-white">
                  {groupList.map(c => {
                    const avatarSrc = c.avatar.startsWith('initials:') ? '' : c.avatar;
                    const subtitle = c.phone.startsWith('+1') ? c.phone : `ID: ${c.username || c.id}`;

                    return (
                      <div
                        key={c.id}
                        onClick={() => onSelectContactChat(c.id)}
                        className="flex items-center px-4 py-3.5 border-b border-[#EEEEEE] hover:bg-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
                      >
                        <div className="relative w-[50px] h-[50px] mr-3 flex-shrink-0">
                          {avatarSrc ? (
                            <img 
                              alt="avatar" 
                              referrerPolicy="no-referrer"
                              className="w-full h-full rounded-xl object-cover border border-gray-100" 
                              src={avatarSrc}
                            />
                          ) : (
                            <div className="w-full h-full rounded-xl bg-[#07c160] flex items-center justify-center text-white font-bold text-lg">
                              {c.name.substring(0, 2).toUpperCase()}
                            </div>
                          )}
                        </div>
                        <div className="flex flex-col flex-1 min-w-0">
                          <span className="text-[16px] font-medium text-[#1c1b1b] truncate">
                            {c.name}
                          </span>
                          <span className="text-[14px] text-gray-400 truncate">
                            {subtitle}
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}

          {filteredContacts.length === 0 && (
            <div className="py-24 text-center text-gray-400 text-sm">
              No contacts found
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

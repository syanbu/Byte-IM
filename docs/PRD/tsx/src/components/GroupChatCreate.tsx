import { useState } from 'react';
import { Search, ArrowLeft, Check, CheckCircle2 } from 'lucide-react';
import { Contact } from '../types';

interface GroupChatCreateProps {
  contacts: Contact[];
  onBack: () => void;
  onDone: (selectedContacts: Contact[]) => void;
}

export default function GroupChatCreate({ contacts, onBack, onDone }: GroupChatCreateProps) {
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedIds, setSelectedIds] = useState<string[]>([]);

  // Filter contacts by ignoring ME
  const actualContacts = contacts.filter(c => c.id !== 'me');

  // Group contacts alphabetically based on names
  const alphabet = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '#'];

  const filteredContacts = actualContacts.filter(c => 
    c.name.toLowerCase().includes(searchTerm.toLowerCase()) || 
    c.phone.includes(searchTerm)
  );

  const getLetterGroup = (contact: Contact): string => {
    const firstChar = contact.name.trim().charAt(0).toUpperCase();
    if (/^[A-Z]$/.test(firstChar)) return firstChar;
    // Check Chinese characters pinyin representation or standard initials
    if (contact.name.startsWith('阿') || contact.name.startsWith('爱') || contact.name.startsWith('艾') || contact.name.toLowerCase().startsWith('aa') || contact.name.toLowerCase().startsWith('ak')) {
      return 'A'; // Matches AA703, Ah, Ai, Ak
    }
    if (contact.name.startsWith('北')) return 'B';
    return '#';
  };

  // Grouped contacts mapped to letters
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

  const toggleSelection = (contactId: string) => {
    if (selectedIds.includes(contactId)) {
      setSelectedIds(selectedIds.filter(id => id !== contactId));
    } else {
      setSelectedIds([...selectedIds, contactId]);
    }
  };

  const selectedCount = selectedIds.length;

  const handleCreateSubmit = () => {
    if (selectedCount === 0) return;
    const selectedList = contacts.filter(c => selectedIds.includes(c.id));
    onDone(selectedList);
  };

  const handleAlphabetClick = (letter: string) => {
    const element = document.getElementById(`group-${letter}`);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  };

  return (
    <div className="w-full h-full bg-[#fcf9f8] flex flex-col relative overflow-hidden">
      {/* Navigation Header */}
      <header className="bg-[#fcf9f8] pt-12 pb-3 px-4 flex items-center justify-between border-b border-gray-100 flex-shrink-0">
        <button 
          onClick={onBack}
          className="flex items-center w-1/4 text-gray-800 cursor-pointer"
        >
          <ArrowLeft className="w-6 h-6" />
        </button>
        <h1 className="text-lg font-semibold text-gray-900 text-center w-2/4">
          发起群聊
        </h1>
        <div className="flex justify-end w-1/4">
          <button
            onClick={handleCreateSubmit}
            disabled={selectedCount === 0}
            className={`px-4 py-1.5 rounded text-sm font-medium transition-colors cursor-pointer ${
              selectedCount > 0 
                ? 'bg-[#07c160] text-white' 
                : 'bg-[#e1e1e1] text-white cursor-not-allowed'
            }`}
          >
            {selectedCount > 0 ? `完成(${selectedCount})` : '完成'}
          </button>
        </div>
      </header>

      {/* Search Bar section */}
      <section className="px-4 py-3 bg-[#fcf9f8] flex-shrink-0">
        <div className="relative">
          <div className="absolute inset-y-0 left-3 flex items-center pointer-events-none">
            <Search className="w-4 h-4 text-gray-400" />
          </div>
          <input
            type="text"
            className="w-full bg-white border-none rounded-md py-2.5 pl-10 pr-4 text-sm placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-[#07c160]"
            placeholder="搜索"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
          />
        </div>
      </section>

      {/* Scroller Canvas Area */}
      <main className="flex-1 overflow-y-auto bg-white pb-12">
        {alphabet.map(letter => {
          const groupContacts = grouped[letter] || [];
          if (groupContacts.length === 0) return null;

          return (
            <div key={letter} id={`group-${letter}`} className="scroll-mt-16">
              {/* Index indicator header */}
              <div className="bg-[#f6f3f2] px-4 py-1 text-xs text-gray-500 font-medium">
                {letter}
              </div>

              {/* Rows */}
              <div className="divide-y divide-gray-50">
                {groupContacts.map(c => {
                  const isSelected = selectedIds.includes(c.id);
                  const avatarSrc = c.avatar.startsWith('initials:') ? '' : c.avatar;

                  return (
                    <div 
                      key={c.id}
                      onClick={() => toggleSelection(c.id)}
                      className="flex items-center px-4 py-3 border-b border-gray-50 active:bg-gray-100 transition-colors cursor-pointer"
                    >
                      {/* Checkbox circle indicator */}
                      <div 
                        className={`w-6 h-6 rounded-full border-2 mr-4 flex items-center justify-center transition-all ${
                          isSelected 
                            ? 'bg-[#07c160] border-[#07c160] text-white' 
                            : 'border-gray-300'
                        }`}
                      >
                        {isSelected && <Check className="w-3.5 h-3.5 stroke-[3]" />}
                      </div>

                      {/* Contact Face */}
                      {avatarSrc ? (
                        <img 
                          alt="Avatar" 
                          referrerPolicy="no-referrer"
                          className="w-11 h-11 rounded-md mr-4 object-cover border border-gray-100" 
                          src={avatarSrc}
                        />
                      ) : (
                        <div className="w-11 h-11 rounded bg-[#07c160] flex items-center justify-center text-white mr-4 font-bold text-[15px]">
                          {c.name.substring(0, 2).toUpperCase()}
                        </div>
                      )}

                      {/* Display name */}
                      <span className="text-[17px] text-gray-900 truncate">
                        {c.name}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}

        {filteredContacts.length === 0 && (
          <div className="py-20 text-center text-gray-400 text-sm">
            没有找到联系人
          </div>
        )}
      </main>

      {/* Alphabet Index Sidebar */}
      <aside className="absolute right-1 top-[180px] bottom-12 w-6 text-2xs text-gray-500 flex flex-col items-center justify-between leading-none z-10 pointer-events-auto pr-1">
        <span>↑</span>
        <span>☆</span>
        {alphabet.map(letter => (
          <button 
            key={letter}
            onClick={() => handleAlphabetClick(letter)}
            className="w-full text-center hover:text-[#07c160] active:scale-125 focus:outline-none transition-transform font-medium cursor-pointer"
          >
            {letter}
          </button>
        ))}
        <span>#</span>
      </aside>
    </div>
  );
}

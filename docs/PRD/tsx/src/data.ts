import { Contact, Conversation, Message } from './types';

// The Logged-in User Profile
export const CURRENT_USER: Contact = {
  id: 'me',
  name: 'Alex Thompson',
  phone: '+1 234 567 8900',
  avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuABjYMhMbjJaiWKH5VTe4ZqWoCgTqajMF0ikFPakknKgqBJIVSfEo9c7s_ZQ3I6BxxBh9yknIhO2n2kMxxIoWnno9RZ5GQvCfk41ZHKvYzuP1xG5FvX1-PtPC9AG7tI7vOLF3Iob73l6_MbAlMxMQcS3xC2z6-ZQjzrw9O0qwT1cqZxj3QAkKA3mvi0F_PQgutYn53OWHQi6tNHYrhwpSDjh6G-XOMNUUdEY4PcC45uOvPbyBbE0nj168LMT1zPWPOf-EFO99x2jE1_',
  region: '广东 深圳',
  gender: '男',
  username: 'SyanMegumi',
  signature: '暑期可以做到吗？',
  ringtone: 'Love Song',
  myAddress: '深圳市南山区深南大道9988号',
  invoiceTitle: '个人 / 腾讯科技'
};

export const INITIAL_CONTACTS: Contact[] = [
  {
    id: 'alex_rivera',
    name: 'Alex Rivera',
    phone: '+1 (555) 321-7890',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCSxJ7nwwMt4aon7WY2dIJWVuhLIt0GgImIRdxAE9bSbAruwW4q4J1-uaXQv4KKpWdk3aqd3SN_6FFN5L0P6F1UuKEBxElr884QF4fpsc25MGqV5Aqvhr8z8-_c3O4f4GCWlHzRlpGtrPxqlqpHcRJft5PEpxlJRrMP7IZVhdMyzaJhXLo2Va32jvp_QxvzYANc2M5jR5zuW4e_Me0NgDf02IXffkx8KTNlVg-PExRme43C_rlXr1P_BE9r8lKLkWxOyk2TVoMxfIaN',
    username: 'alex_rivera_dev',
    gender: '女',
    region: 'California, US',
    signature: 'Writing code and building systems',
    ringtone: 'Default'
  },
  {
    id: 'alex_thompson',
    name: 'Alex Thompson (Contacts List Info)',
    phone: '+1 (555) 765-4321',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuBHQ2_2ImLEmUeGsxV3vv6d_If8UK1swW6549wVikP9QrBemYfTyJEKyFVgs-N8LXP1A2EyuwcxNJIBb5AD9tCI7tQjAQ9yu3tUYXBAw0Ta9C6YviKYJiH1h9JeX_R5pkbIQnsBKr6td7pBM1PJ8dpUkUI1eciv4hjR5_UgF67_mnQm44kGrsO5QDB9QYWYLA9d3yQU6CWWy2JYCJEV0xlviQJp8xKR9_mWycaKcNTJhaXhH9WDUuEdevdafrTaMYq6isdUYAa1sGPZ',
    username: 'alex_t_99',
    gender: '男',
    region: 'London, UK',
    signature: 'Product Strategy Sync Admin',
    ringtone: 'Cosmic Ring'
  },
  {
    id: 'amanda_chen',
    name: 'Amanda Chen',
    phone: '+1 (555) 012-3456',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCNlLEH2j0H6qGUHC0NL4aPp6-t6sRLtMbQAltxVMqTAxqZUdHdfX3r7Mgu-FMTkWprPB1uc-_IP7g4VZPyiMmUmcY2LMCLN6SMq6a9SMy9kqg2NcUjR-vSInKcSOZwAU_O6a1G5u1ctvLyWFlscC2OJRBxRHrunR6hsfQlImBqmwUXhjHwaIEYgwIODdDEparzmpFu3vuGUqSmA9SU86bIbdC-PZihWIL9SjyyRGzFD8QDCXccQZrCsYTlJW7MggZj-2VRGMk1A0_0',
    username: 'amanda_c',
    gender: '女',
    region: 'Singapore',
    signature: 'In love with design details',
    ringtone: 'Playful Breeze'
  },
  {
    id: 'ben_smith',
    name: 'Ben Smith',
    phone: '+1 (555) 234-5678',
    avatar: 'initials:BS',
    username: 'b_smith_vault',
    gender: '男',
    region: 'Sydney, AU',
    signature: 'Keep it secure, keep it private',
    ringtone: 'Vibrate Only'
  },
  {
    id: 'david_miller',
    name: 'David Miller',
    phone: '+1 (555) 789-1011',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuC2VNHERl1fjKHCdVVdsoeFrlt1TMBz133wwGRFeqvPjDraclsh-3cMQTWFLPCas4uPuLCNzysvRKIJ_iK8ivYOKyN-5tbHFyepmUPmTRx2kCcqO5v6OZj6DaOepMaR6VXwZ0BPOqoaJGtfdSbjJqA2N1l9JWAXppBO_G_viq1l9pzmHJSF6SKwobftFeCiNUhSvTtnU1pXWtMZwNpm0HjwpyihTsrbUqzzh1nHDZ7sPWvylGsYO92qTD6BQ51t8052UonZENdU9TQ0',
    username: 'david_m_hq',
    gender: '男',
    region: 'New York, US',
    signature: 'Security & Integrity is paramount',
    ringtone: 'Corporate'
  },
  {
    id: 'elena_rod',
    name: 'Elena Rodriguez',
    phone: '+1 (555) 890-1234',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuAjTvqJmWvJw3QIBmn7O0nRBuBUsKIJXDhMK0ii2UWDV85tIJd-_La99Tss1RnfYiurGT-IdjKPHpJGq1suJbTFBzdpDk_TEHHrqy3YeiEhhvp-VCIrAx0fM1ajnVniKGu2xRuJCpSPRX4Jf6-7gWc62ikIZvktiKSer-LX8kJJGFf5CgjvRZE7AzvzFUpnGk42j7z6I4496mPj2QQKw0oW8veQcExwb-3vvtPjDeZV3RiHi6D2nzUensS2wl3b9J0jAS3I3JxOzjkN',
    username: 'elena_rod',
    gender: '女',
    region: 'Madrid, ES',
    signature: 'La vida es bella',
    ringtone: 'Guitar Strum'
  },
  {
    id: 'sarah_chen',
    name: 'Sarah Chen',
    phone: '+1 (555) 456-7890',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuD1g0TrpWGafY_Q_8F_2Ql5SjPzj9N8cBgpuy4B7H-8572TVnQ9m7Od0NLW4TTTajU9DDgRCvCcNMWXx5B3XT-Z4INzwuI558uwxsVJksa4IE5Xa-vdM2XQN5WNywCeA42vdAiK2Qjdue-O8o4T567zAjso1hZgifeVXFizbGi24Reqk4Wi_98ZEe0tqMtexcJQSLzcnNI2v5EPO32C_zAdCF2s0pysS_3kqCNNpfDq42vmNvLAImdY_Csc1gcr3a9wUacxonw-y-TH',
    username: 'sarah_c_tech',
    gender: '女',
    region: 'Beijing, CN',
    signature: 'Product Manager @ByteIM',
    ringtone: 'Zen Chord'
  },
  {
    id: 'jordan_smith',
    name: 'Jordan Smith',
    phone: '+1 (555) 567-8901',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuDV9v4PPT_vpfNLpO1O6KZPr3HHD2DCyUdVDjicDN6BGwGJ0Dyb2w-pvGImUcC_3hsPW6VqVsL1uIpjth2466iubalzBgu8litjx0YK6xsaa60af2QPm7-FmlPiF218fA9acQW25LonhFJmS7Jxx11OeWXqE4Xs-hWG1z6hdQVnZFaRg41a7rvxmmGnRJ05viwR7THXJ79ptaCH_guyFMsoRMCPOLyTq1wonecqwYdycU1hZjRQ0ARKgYjNzlBKcnt1XDZpBYRbPYoh',
    username: 'jordan_design',
    gender: '男',
    region: 'San Francisco, US',
    signature: 'Visual Designer and UX explorer',
    ringtone: 'Upbeat'
  },
  {
    id: 'marcus_smith',
    name: 'Marcus Smith',
    phone: '+1 (555) 678-9012',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuBzkaRCJIeqbv8BiydTvOojVJtMkg-oqsduGGncYgoOCFbmQz8qkrSsXyRmVgRXo24e_Pn5v-gpTt58D_L3WChSb5_PJV07Se_ZT3TNCPJr2A6OvyOGmVgbWUarSc_qDtQn-b2Ft1Y0E9F2UIxG3RBH7b9j-RmKKdUwlwFI-xizGqT1ovlkvDvMxfaFNifWsMBirKA1m57QpVxy7rj2xKVo5Un7sOSnTzuLw96RJkvqZqyk5EPpnqAwMVRFo0Vqzl4bu6To4ysNrpdH',
    username: 'marcus_devops',
    gender: '男',
    region: 'Munich, DE',
    signature: 'Deploying servers 24/7',
    ringtone: 'Mechanical Beep'
  },
  {
    id: 'aa703_tianshun',
    name: 'AA703天顺',
    phone: '+86 138-1234-5678',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCkZRBMBJXThEgv7OthmzuvWcS93MBH87XZKI9_3UX6cpjMUtWbO2FdJUeyf2SnGsCPsXZHNR4NvsejFisUUB6QQR3KDAx4FZ9ELMkcFbxgWdRb8YK2AYuLjkumbZWLL50w-HwNWiLz88gReo58-Lo5G6aJsrb22b09WXCw_iZmKkC9S0k47tZ51BQILJbUWlsydN-QL6vfj74Ab2sUHHccRDxFtYA7tmYjFA0fKU-0I-yMdGmp9yY-oto4b_nuXRlPfOxVuscCOuP_',
    username: 'tianshun_703',
    gender: '男',
    region: '广东 广州',
    signature: '诚信合作，顺水推舟',
    ringtone: 'Classic'
  },
  {
    id: 'afu_heman',
    name: '阿福的小伙伴-何满',
    phone: '+86 139-4321-8765',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuAwzpM5V7H1EFKU0eis0NFvz1dWMBtVfYP3YD2C-IBrPVjBdZpxEEVJqLGpUIPOE3SGKaRKpEmUACM5MLK5CnezXIhvFeXhpIGvg9coM_PC47ITx71u7cgZIkMvh5wd9K2NiBU2glZJGRSetu0s0oh6l6sIkGTRrrW6jT39eq3f-QHa6jItm0JXZPYsS7RvCH0ZU5J-81eiCntKP_K_WXNf5410MvGx40xH5SvQgm1vOh4LV0XrEGxyykLI6DDbNROu8ss3BpuBmNF5',
    username: 'heman_afu',
    gender: '男',
    region: '北京 朝阳',
    signature: '快乐生活，踏实工作',
    ringtone: 'Acoustic Guitar'
  },
  {
    id: 'aifen',
    name: '爱芬',
    phone: '+86 135-2233-4455',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCkHUBZYuOiT0XO5Sbe7gDzjfQoERuNP5Yps1KsHNIrilWmhqdyNQwIlSs-gkA1esHLG2ig3H3olYZxjdYHbm8sci0rHclykG6oJCpP5Yg-4RqfKAnphuzrns4oLjyoLXC6qEf1wsv3L3--O0YcNtj3F57wjZSbTx2qJNnUs4qFBFup6Jjo1TXpVy_XA3T-EtpPdnMpaFSoAqW_zPD9L4_X6PMAIJ1CquRhoqUrCM6p5NnL45fKM4U50oZz23_Ki15LNI1-KwzeFda5',
    username: 'aifen_sweet',
    gender: '女',
    region: '浙江 杭州',
    signature: '芬芳满园，心之向往',
    ringtone: 'Harp Harmony'
  },
  {
    id: 'aike_dan',
    name: '艾克丹',
    phone: '+86 137-5555-6666',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCEiclVWdCRluIz2ZAsRnniRYYCopk5_U0CVksXlXS0tE_NnmoMw9_qkJ4kXXky-xwgAPzjhe9YT4x7XbVgFSp_bdwpOWeRe8L7uuKdFYAjbHqpi45pudNztOdlKAPgehpt1GJVKDNyuwFKbB5O12aEGMQWj9GjzA_5HYY6FKh69LUPALyIESTLnmMmidMo-5o2ysHcweHhdd2vb_yrwJdiD3DgE1TNuKdNM8m-qOCasobt1TVBkduhmD49V27EmI9gc7RK5meKBgTa',
    username: 'aikedan_k',
    gender: '女',
    region: '新疆 乌鲁木齐',
    signature: '天山雪莲，纯洁无瑕',
    ringtone: 'Flute Echo'
  },
  {
    id: 'aimeng',
    name: 'ai派蒙',
    phone: '+86 150-1111-2222',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuDZ0mD1rbZQ7Z8xWOyIp9ph7gZwp9ffO0V_JrxSqbnZoIZc5qBfxOFiuk5RWWL-1OaD2sjvS4LFy2aHZk_we7tu0NnSumJ1XRd4CbNP9hWCpkHLH3JjyfeLe1pocD1sbcr5mMcoK9nxzndi02siHz_rOTbITwzZmxc70wKvdwQtRUFUsjfDDdKO64UmQ6EJb8RTc8PLrSdTOx0SQELPqbjxRKkiibHwFxkMubORmEG1gpwi-6TbLXnqal4roH8voHZwcnDy3SnH2hGp',
    username: 'paimon_best_guide',
    gender: '女',
    region: '提瓦特 蒙德',
    signature: '应急食品可不是我哦！',
    ringtone: 'Arcade Ding'
  },
  {
    id: 'ai_pttp',
    name: 'Ai Pttp',
    phone: '+86 133-9999-8888',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCvSKtm6-XMz8ICyYeAlF5wdC2foMuB_OBhOOlMS_FoL92ILXgy4JGFRy6xrl4mPfHlNKArD4aLylCAFw7APLF0X_KYIMJR-vswYayNKdhuZiTV8NWHajf_jnxkc3VDdZykSYeWfsuetpGh-55iUaHL8p3f0BN_zJwAnNdqYI8SBS0fVnfvL1rR4H42ANnBiY9_nTNmNVJ6ULaFbSfecBx6WwFSRyxsYoD49tEiALCNpaoqANxDt61JBvyjCkeMcznCtmWUOqMYU84U',
    username: 'ai_pttp_official',
    gender: '男',
    region: '上海 徐汇',
    signature: 'Keep debugging the protocols',
    ringtone: 'Analog Beep'
  },
  {
    id: 'jiabao_glasses',
    name: 'A嘉宝新视界眼镜13691...',
    phone: '+86 136-9111-2222',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuD_JekRc37ggEou25Pb2fNZgF9E-ZuZenCuX_5axYJ9JC-kVq1l4-0kfxA4o32FjWg_zAcAsEmRH3jefRVzKNjVTC4oM3osYwoBbS1aWUtxsRIZ6UZRTeKez_KwPfNwMQ-uvfKaIDEHWbK0G5AHF23ijphhzPRf71cr7n7vKmxt6z4lC48jXm9V1csXvyiKozJQfNKmR565Droy7wCajEeFBy9JM4sDTdx01EnaKXSTYPisiwkYOzHW-dhjzCoRugJ2jmUs1yOfmbXa',
    username: 'jiabao_optician',
    gender: '男',
    region: '江苏 南京',
    signature: '嘉宝眼镜，视界无限清晰',
    ringtone: 'Bells Tone'
  },
  {
    id: 'ak_coding',
    name: 'ak_coding ( 23 点打烊)',
    phone: '+86 188- cod-ak',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuAg_e9z-xG5vtzxtxeJCROnCnhlNSKOGDNNr7pVzHkDriG0WFHiKLwWLOlzwPnPI2R53LdrkBKviAaHIkFD6__AR1m7G_wJQHuiEi69M5hAOveBYPC4hFLVD-fgo468ooju-F_O__7Fs7SxW9XCOjHbzxAAfFGpoh-ohg3YlpsJMkStnq5ABMoYXKNFb3MwrtdEOHXDDtZULfxWnSvuZeO5EnrukospjVnGUIZAtqCV400aHaNrP6V43_GzY4MFtYxYJGqylYQVjRnG',
    username: 'ak_coding_vault',
    gender: '男',
    region: '四川 成都',
    signature: '熬夜修Bug，23点准时强制休眠',
    ringtone: 'Dreamy Chime'
  },
  {
    id: 'amei',
    name: '啊梅',
    phone: '+86 158-7777-6666',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCtjcSmayIdjT_RrEDC66ojpOmbiT21QpBEchfNa5UmHlK-cvnslIgncwOaSb9yKn-Wsh1SB-8pLqycRs91HnM9lfJeVF1sU-49Z-5El8KaytjG67hAEivGr80ZxdyVm9Web4rVzc-KcKxkdQ39nkpWySQgCTBfohES50LqhuWw2wkzeFMmx9cymA67O1i4YUymDpyLiMUUqPFU_DU7oaIwvw0QDMPqybuADCJey8z1-ysT9HJadbhdtJ9tkBYgvYWR_9rIzXJNbKK3',
    username: 'amei_chuan',
    gender: '女',
    region: '湖北 武汉',
    signature: '每天都要美滋滋',
    ringtone: 'Harmonica'
  },
  {
    id: 'ami',
    name: '阿米',
    phone: '+86 177-3344-5566',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuBpkbZzyirUBEsTpXOsOwZs2ENESG3KHiPSuSTz8h5bVEdmf4bh5Y5EC-rE1ularIPicxEJuTD7n1aVoBAGHcBF_SPHcTHUlw1pIaWNMzMlPZMNIibviaSbX75MRXTr8Lc7YuXKNJAyRLgLfDNktlDF3FNy2UIoqjY-jc5RhwpGdBN4SwkkIOuu9miZVhqXQEhN4T4QsUGXix96XTYs8xhfhAmnfdWKt2AZBCQ99sOUPB6PnAqoH1H03n81qruUKHUo_DT9NDr51rYH',
    username: 'ami_cloud',
    gender: '女',
    region: '吉林 长春',
    signature: '生活是一面镜子，你笑它就笑',
    ringtone: 'Synth Rise'
  },
  {
    id: 'polaris',
    name: '北极星',
    phone: '+86 182-0000-1111',
    avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuA5EFtvazNiEJrZGGmy-jAzvxJIEWXxxaJN8XH5WA_H4bMXACvjh_PmtbbsJYVCW-zWvww7A5x9-yA6RgFRu5mjo4hKc4juQdpuUfK-QPnJiAa8VOcSpWzPSAQKME2pAFcMnjIZvPbFNRWksTAGcqhwNW-rKfnbaINznONEWikj1BwxpbeLNNlyP8oSzA-hzdIlF4Tfwsvz7MjquYx6uJkmAOZ-QWvTQj3xVGrfGsn-Cw7IvOWsx8ZvLQAJoegzEPsj4Hf3npkdA7HB',
    username: 'polaris_star',
    gender: '女',
    region: '黑龙江 哈尔滨',
    signature: '指引未来的那颗星',
    ringtone: 'Polar Star Bell'
  }
];

export const getInitialConversations = (): Conversation[] => {
  return [
    {
      id: 'alex_rivera',
      name: 'Alex Rivera',
      isGroup: false,
      avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCSxJ7nwwMt4aon7WY2dIJWVuhLIt0GgImIRdxAE9bSbAruwW4q4J1-uaXQv4KKpWdk3aqd3SN_6FFN5L0P6F1UuKEBxElr884QF4fpsc25MGqV5Aqvhr8z8-_c3O4f4GCWlHzRlpGtrPxqlqpHcRJft5PEpxlJRrMP7IZVhdMyzaJhXLo2Va32jvp_QxvzYANc2M5jR5zuW4e_Me0NgDf02IXffkx8KTNlVg-PExRme43C_rlXr1P_BE9r8lKLkWxOyk2TVoMxfIaN',
      unreadCount: 3,
      isMentionedMe: true,
      lastMessage: "Let's meet for the sprint review!",
      lastMessageTime: '14:30',
      messages: [
        {
          id: 'ar_1',
          senderId: 'alex_rivera',
          senderName: 'Alex Rivera',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCSxJ7nwwMt4aon7WY2dIJWVuhLIt0GgImIRdxAE9bSbAruwW4q4J1-uaXQv4KKpWdk3aqd3SN_6FFN5L0P6F1UuKEBxElr884QF4fpsc25MGqV5Aqvhr8z8-_c3O4f4GCWlHzRlpGtrPxqlqpHcRJft5PEpxlJRrMP7IZVhdMyzaJhXLo2Va32jvp_QxvzYANc2M5jR5zuW4e_Me0NgDf02IXffkx8KTNlVg-PExRme43C_rlXr1P_BE9r8lKLkWxOyk2TVoMxfIaN',
          text: 'Hi Alex, are you available?',
          timestamp: '14:28',
          isSelf: false
        },
        {
          id: 'ar_2',
          senderId: 'alex_rivera',
          senderName: 'Alex Rivera',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCSxJ7nwwMt4aon7WY2dIJWVuhLIt0GgImIRdxAE9bSbAruwW4q4J1-uaXQv4KKpWdk3aqd3SN_6FFN5L0P6F1UuKEBxElr884QF4fpsc25MGqV5Aqvhr8z8-_c3O4f4GCWlHzRlpGtrPxqlqpHcRJft5PEpxlJRrMP7IZVhdMyzaJhXLo2Va32jvp_QxvzYANc2M5jR5zuW4e_Me0NgDf02IXffkx8KTNlVg-PExRme43C_rlXr1P_BE9r8lKLkWxOyk2TVoMxfIaN',
          text: 'We have some code updates to discuss regarding ByteIM core client.',
          timestamp: '14:29',
          isSelf: false
        },
        {
          id: 'ar_3',
          senderId: 'alex_rivera',
          senderName: 'Alex Rivera',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuCSxJ7nwwMt4aon7WY2dIJWVuhLIt0GgImIRdxAE9bSbAruwW4q4J1-uaXQv4KKpWdk3aqd3SN_6FFN5L0P6F1UuKEBxElr884QF4fpsc25MGqV5Aqvhr8z8-_c3O4f4GCWlHzRlpGtrPxqlqpHcRJft5PEpxlJRrMP7IZVhdMyzaJhXLo2Va32jvp_QxvzYANc2M5jR5zuW4e_Me0NgDf02IXffkx8KTNlVg-PExRme43C_rlXr1P_BE9r8lKLkWxOyk2TVoMxfIaN',
          text: "Let's meet for the sprint review!",
          timestamp: '14:30',
          isSelf: false,
          isMentioned: true
        }
      ],
      members: ['me', 'alex_rivera']
    },
    {
      id: 'product_design_team',
      name: 'Product Design Team',
      isGroup: true,
      avatar: 'group_avatar',
      avatars: [
        'https://lh3.googleusercontent.com/aida-public/AB6AXuB1tzPHn6qz99SHo8XcOIUESs-l5ND2MOiQ0rhH-0ai_nzo5vddv93IZJzYydqMVnoCUsAuzs6_K0IAmXL5XhngXanF9FM2oGls9SyFSqkihn9YB5kyprfyHw06uFkjrdN-yvgcEWSa2FobJPa_0UhKmaWUm7qkVde4xSd9MjCc1opDO1OKImuPazqFuHbN6DDEOxLzYlJ8H-dQYUakyWtSimF0CK0pbua9EaoRxJ3tR2be9Bbn4LvIbMo9qw9IpfDHYY9OWi4c2FnK',
        'https://lh3.googleusercontent.com/aida-public/AB6AXuBD9HMLmfdsfxPUAGvAGh9zRG7zlOgMIMpL4591Z2sKzc-dsOGcga1UyEFVYRQE7mFOq23cHbd2GsNXz27vW3jBFypWW9kV8f8IOrFICL81UXalXYbKdKd2_tIEGw_88nHEc1E373qk1g2F5ew-xwa6hNqJZTFlBPxmJxxlcm83Mo25mnT5_eIiR6kYg55rOTj5xWMLQobjWVYeoyyxdrdE5OtKoQFGiVpNK4FBXhCHoSA-i76I7_kWfb3ywV62ShaQL5j0_czBFyEf',
        'https://lh3.googleusercontent.com/aida-public/AB6AXuBPM1yNXlMwv0WdwL6nptRAh5sudHoKZGe9LQMAShLjDuxqEfjPQ_dqkm-pAFvS2e67doj6p5hA6ih_3xqXynUvPelBqayh9WWrQ7VR9Z00mIjsuTk3CPksX48qAPAJwagD61y-uWj3Srs_-8RNZqJyLZims3x4iEwq_tVbZOMXaFRkdgrmd1joLal3xFCyEkY7HXNBloycaD8HO5KdTU2E8y2jns8uJ0h-GgBNw8f1KXOHDuUHyhnMHDOme-uPccrX1k_Xs_iSf6Q6'
      ],
      unreadCount: 0,
      lastMessage: "Jordan: The new prototypes are ready for review...",
      lastMessageTime: '12:05',
      messages: [
        {
          id: 'pdt_1',
          senderId: 'jordan_smith',
          senderName: 'Jordan Smith',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuDV9v4PPT_vpfNLpO1O6KZPr3HHD2DCyUdVDjicDN6BGwGJ0Dyb2w-pvGImUcC_3hsPW6VqVsL1uIpjth2466iubalzBgu8litjx0YK6xsaa60af2QPm7-FmlPiF218fA9acQW25LonhFJmS7Jxx11OeWXqE4Xs-hWG1z6hdQVnZFaRg41a7rvxmmGnRJ05viwR7THXJ79ptaCH_guyFMsoRMCPOLyTq1wonecqwYdycU1hZjRQ0ARKgYjNzlBKcnt1XDZpBYRbPYoh',
          text: 'Hey everyone, did you see the latest draft for the SelfHostedIM roadmap?',
          timestamp: 'Yesterday 14:30',
          isSelf: false
        },
        {
          id: 'pdt_2',
          senderId: 'me',
          senderName: 'Alex Thompson',
          senderAvatar: CURRENT_USER.avatar,
          text: 'Yes, I just reviewed it. Looks solid but we need to prioritize the E2EE module.',
          timestamp: '14:42',
          isSelf: true,
          status: 'read'
        },
        {
          id: 'pdt_3',
          senderId: 'me',
          senderName: 'Alex Thompson',
          senderAvatar: CURRENT_USER.avatar,
          text: 'You recalled a message',
          timestamp: '14:43',
          isSelf: true,
          isSystem: true
        },
        {
          id: 'pdt_4',
          senderId: 'sarah_chen',
          senderName: 'Sarah Chen',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuD1g0TrpWGafY_Q_8F_2Ql5SjPzj9N8cBgpuy4B7H-8572TVnQ9m7Od0NLW4TTTajU9DDgRCvCcNMWXx5B3XT-Z4INzwuI558uwxsVJksa4IE5Xa-vdM2XQN5WNywCeA42vdAiK2Qjdue-O8o4T567zAjso1hZgifeVXFizbGi24Reqk4Wi_98ZEe0tqMtexcJQSLzcnNI2v5EPO32C_zAdCF2s0pysS_3kqCNNpfDq42vmNvLAImdY_Csc1gcr3a9wUacxonw-y-TH',
          text: 'Agreed. @Admin can you please update the milestone dates in the shared doc?',
          timestamp: '14:45',
          isSelf: false,
          isMentioned: true
        },
        {
          id: 'pdt_5',
          senderId: 'jordan_smith',
          senderName: 'Jordan Smith',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuDV9v4PPT_vpfNLpO1O6KZPr3HHD2DCyUdVDjicDN6BGwGJ0Dyb2w-pvGImUcC_3hsPW6VqVsL1uIpjth2466iubalzBgu8litjx0YK6xsaa60af2QPm7-FmlPiF218fA9acQW25LonhFJmS7Jxx11OeWXqE4Xs-hWG1z6hdQVnZFaRg41a7rvxmmGnRJ05viwR7THXJ79ptaCH_guyFMsoRMCPOLyTq1wonecqwYdycU1hZjRQ0ARKgYjNzlBKcnt1XDZpBYRbPYoh',
          text: 'The new prototypes are ready for review...',
          timestamp: '12:05',
          isSelf: false
        }
      ],
      members: ['me', 'sarah_chen', 'jordan_smith', 'alex_rivera']
    },
    {
      id: 'sarah_chen',
      name: 'Sarah Chen',
      isGroup: false,
      avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuD1g0TrpWGafY_Q_8F_2Ql5SjPzj9N8cBgpuy4B7H-8572TVnQ9m7Od0NLW4TTTajU9DDgRCvCcNMWXx5B3XT-Z4INzwuI558uwxsVJksa4IE5Xa-vdM2XQN5WNywCeA42vdAiK2Qjdue-O8o4T567zAjso1hZgifeVXFizbGi24Reqk4Wi_98ZEe0tqMtexcJQSLzcnNI2v5EPO32C_zAdCF2s0pysS_3kqCNNpfDq42vmNvLAImdY_Csc1gcr3a9wUacxonw-y-TH',
      unreadCount: 0,
      lastMessage: "How are you? I saw your update on the internal portal.",
      lastMessageTime: 'Yesterday',
      messages: [
        {
          id: 'sc_1',
          senderId: 'sarah_chen',
          senderName: 'Sarah Chen',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuD1g0TrpWGafY_Q_8F_2Ql5SjPzj9N8cBgpuy4B7H-8572TVnQ9m7Od0NLW4TTTajU9DDgRCvCcNMWXx5B3XT-Z4INzwuI558uwxsVJksa4IE5Xa-vdM2XQN5WNywCeA42vdAiK2Qjdue-O8o4T567zAjso1hZgifeVXFizbGi24Reqk4Wi_98ZEe0tqMtexcJQSLzcnNI2v5EPO32C_zAdCF2s0pysS_3kqCNNpfDq42vmNvLAImdY_Csc1gcr3a9wUacxonw-y-TH',
          text: 'Agreed. Can you please update the milestone dates in the shared doc?',
          timestamp: 'Yesterday 14:30',
          isSelf: false
        },
        {
          id: 'sc_2',
          senderId: 'me',
          senderName: 'Alex Thompson',
          senderAvatar: CURRENT_USER.avatar,
          text: 'Yes, I just reviewed it. Looks solid but we need to prioritize the E2EE module.',
          timestamp: '14:42',
          isSelf: true,
          status: 'read'
        },
        {
          id: 'sc_3',
          senderId: 'me',
          senderName: 'Alex Thompson',
          senderAvatar: CURRENT_USER.avatar,
          text: 'You recalled a message',
          timestamp: '14:43',
          isSelf: true,
          isSystem: true
        },
        {
          id: 'sc_4',
          senderId: 'sarah_chen',
          senderName: 'Sarah Chen',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuD1g0TrpWGafY_Q_8F_2Ql5SjPzj9N8cBgpuy4B7H-8572TVnQ9m7Od0NLW4TTTajU9DDgRCvCcNMWXx5B3XT-Z4INzwuI558uwxsVJksa4IE5Xa-vdM2XQN5WNywCeA42vdAiK2Qjdue-O8o4T567zAjso1hZgifeVXFizbGi24Reqk4Wi_98ZEe0tqMtexcJQSLzcnNI2v5EPO32C_zAdCF2s0pysS_3kqCNNpfDq42vmNvLAImdY_Csc1gcr3a9wUacxonw-y-TH',
          text: 'Agreed. Can you please update the milestone dates in the shared doc?',
          timestamp: '14:45',
          isSelf: false
        },
        {
          id: 'sc_5',
          senderId: 'sarah_chen',
          senderName: 'Sarah Chen',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuD1g0TrpWGafY_Q_8F_2Ql5SjPzj9N8cBgpuy4B7H-8572TVnQ9m7Od0NLW4TTTajU9DDgRCvCcNMWXx5B3XT-Z4INzwuI558uwxsVJksa4IE5Xa-vdM2XQN5WNywCeA42vdAiK2Qjdue-O8o4T567zAjso1hZgifeVXFizbGi24Reqk4Wi_98ZEe0tqMtexcJQSLzcnNI2v5EPO32C_zAdCF2s0pysS_3kqCNNpfDq42vmNvLAImdY_Csc1gcr3a9wUacxonw-y-TH',
          text: 'How are you? I saw your update on the internal portal.',
          timestamp: 'Yesterday',
          isSelf: false
        }
      ],
      members: ['me', 'sarah_chen']
    },
    {
      id: 'system_updates',
      name: 'System Updates',
      isGroup: false,
      avatar: 'system_bell',
      unreadCount: 0,
      lastMessage: "Security patch applied. Please verify your recovery mail.",
      lastMessageTime: 'Monday',
      messages: [
        {
          id: 'sys_1',
          senderId: 'system',
          senderName: 'System',
          senderAvatar: 'bell',
          text: 'Security patch applied. Please verify your recovery mail.',
          timestamp: 'Monday',
          isSelf: false
        }
      ],
      members: ['me']
    },
    {
      id: 'marcus_smith',
      name: 'Marcus Smith',
      isGroup: false,
      avatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuBzkaRCJIeqbv8BiydTvOojVJtMkg-oqsduGGncYgoOCFbmQz8qkrSsXyRmVgRXo24e_Pn5v-gpTt58D_L3WChSb5_PJV07Se_ZT3TNCPJr2A6OvyOGmVgbWUarSc_qDtQn-b2Ft1Y0E9F2UIxG3RBH7b9j-RmKKdUwlwFI-xizGqT1ovlkvDvMxfaFNifWsMBirKA1m57QpVxy7rj2xKVo5Un7sOSnTzuLw96RJkvqZqyk5EPpnqAwMVRFo0Vqzl4bu6To4ysNrpdH',
      unreadCount: 0,
      lastMessage: "The logs were sent to the server.",
      lastMessageTime: '20 Oct',
      messages: [
        {
          id: 'ms_1',
          senderId: 'marcus_smith',
          senderName: 'Marcus Smith',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuBzkaRCJIeqbv8BiydTvOojVJtMkg-oqsduGGncYgoOCFbmQz8qkrSsXyRmVgRXo24e_Pn5v-gpTt58D_L3WChSb5_PJV07Se_ZT3TNCPJr2A6OvyOGmVgbWUarSc_qDtQn-b2Ft1Y0E9F2UIxG3RBH7b9j-RmKKdUwlwFI-xizGqT1ovlkvDvMxfaFNifWsMBirKA1m57QpVxy7rj2xKVo5Un7sOSnTzuLw96RJkvqZqyk5EPpnqAwMVRFo0Vqzl4bu6To4ysNrpdH',
          text: 'Setup complete on Cloud Run. Server ready.',
          timestamp: '20 Oct 11:20',
          isSelf: false
        },
        {
          id: 'ms_2',
          senderId: 'marcus_smith',
          senderName: 'Marcus Smith',
          senderAvatar: 'https://lh3.googleusercontent.com/aida-public/AB6AXuBzkaRCJIeqbv8BiydTvOojVJtMkg-oqsduGGncYgoOCFbmQz8qkrSsXyRmVgRXo24e_Pn5v-gpTt58D_L3WChSb5_PJV07Se_ZT3TNCPJr2A6OvyOGmVgbWUarSc_qDtQn-b2Ft1Y0E9F2UIxG3RBH7b9j-RmKKdUwlwFI-xizGqT1ovlkvDvMxfaFNifWsMBirKA1m57QpVxy7rj2xKVo5Un7sOSnTzuLw96RJkvqZqyk5EPpnqAwMVRFo0Vqzl4bu6To4ysNrpdH',
          text: 'The logs were sent to the server.',
          timestamp: '20 Oct 14:10',
          isSelf: false
        }
      ],
      members: ['me', 'marcus_smith']
    }
  ];
};

// Fun dynamic replies to simulate talking with contacts!
export const getAutoReply = (contactId: string, userText: string): string => {
  const normalized = userText.toLowerCase().trim();
  
  if (normalized.includes('hello') || normalized.includes('hi') || normalized.includes('你好')) {
    return `Hello! This is ${contactId.replace('_', ' ')}. Glad you messaged. How is it going with ByteIM today?`;
  }
  
  if (normalized.includes('milestone') || normalized.includes('doc') || normalized.includes('E2EE') || normalized.includes('cryptography')) {
    return "Yes! I will synchronize with the Admin core team and review the end-to-end encryption specs. Let's make sure the key exchanges remain fully secure and decoupled.";
  }

  if (normalized.includes('test') || normalized.includes('check') || normalized.includes('debug')) {
    return "Protocol test looks secure and offline operations are in sync. All systems are green!";
  }

  const responses = [
    "Perfect! I will review that immediately.",
    "Agreed. Let's touch base again during the squad sync up.",
    "I'm on a quick call, will drop standard notes in our shared workspace database shortly.",
    "Secure, private and robust - loving the progress we're making on ByteIM!",
    "That makes total sense. Could you please double check if Amanda and Ben are aligned?",
    "Understood. I will push the latest artifact updates on the testing dashboard."
  ];

  return responses[Math.floor(Math.random() * responses.length)];
};

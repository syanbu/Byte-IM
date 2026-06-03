import React, { useState } from 'react';
import { Loader2 } from 'lucide-react';

interface LoginRegisterProps {
  onSuccess: (phone: string) => void;
}

export default function LoginRegister({ onSuccess }: LoginRegisterProps) {
  const [isLoginMode, setIsLoginMode] = useState(true);
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  const toggleAuthMode = () => {
    if (isLoading) return;
    setIsLoginMode(!isLoginMode);
    setErrorMsg('');
  };

  const handleAuthSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (isLoading) return;

    if (!phone.trim() || !password.trim()) {
      setErrorMsg('Please enter your phone number and password.');
      return;
    }

    if (!isLoginMode && password !== confirmPassword) {
      setErrorMsg('Passwords do not match.');
      return;
    }

    setIsLoading(true);
    setErrorMsg('');

    // Simulate API authorization response
    setTimeout(() => {
      setIsLoading(false);
      onSuccess(phone);
    }, 1200);
  };

  return (
    <div className="flex-grow flex flex-col items-center justify-center px-4 pb-20 w-full max-w-[390px] mx-auto min-h-screen">
      <div className="w-full max-w-[342px] text-center mb-10 transition-all duration-300">
        <h1 className="text-[32px] font-bold text-[#1c1b1b] tracking-tight mb-2">ByteIM</h1>
        <p className="text-[17px] text-[#808080]">Secure, private, and entirely yours.</p>
      </div>

      <form 
        onSubmit={handleAuthSubmit} 
        className="w-full max-w-[342px] bg-white rounded-xl border border-[#EEEEEE] overflow-hidden shadow-sm"
      >
        {/* Phone Row */}
        <div className="flex items-center h-14 border-b border-[#EEEEEE] px-4 bg-white">
          <label className="w-[100px] text-[17px] font-normal text-[#1c1b1b]" htmlFor="phone">Phone</label>
          <input
            id="phone"
            type="tel"
            className="flex-grow h-full bg-transparent border-none outline-none text-[17px] text-[#1c1b1b] placeholder:text-gray-300 focus:ring-0 p-0"
            placeholder="Enter phone number"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
          />
        </div>

        {/* Password Row */}
        <div className="flex items-center h-14 border-b border-[#EEEEEE] px-4 bg-white">
          <label className="w-[100px] text-[17px] font-normal text-[#1c1b1b]" htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            className="flex-grow h-full bg-transparent border-none outline-none text-[17px] text-[#1c1b1b] placeholder:text-gray-300 focus:ring-0 p-0"
            placeholder="Enter password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>

        {/* Confirm password field with smooth drawer animation */}
        <div 
          className={`flex items-center px-4 bg-white transition-all duration-300 overflow-hidden ${
            isLoginMode ? 'h-0 opacity-0 border-b-0' : 'h-14 opacity-100 border-b border-[#EEEEEE]'
          }`}
        >
          <label className="w-[100px] text-[17px] font-normal text-[#1c1b1b]" htmlFor="confirm">Confirm</label>
          <input
            id="confirm"
            type="password"
            className="flex-grow h-full bg-transparent border-none outline-none text-[17px] text-[#1c1b1b] placeholder:text-gray-300 focus:ring-0 p-0"
            placeholder="Confirm password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
          />
        </div>
      </form>

      {/* Error Message Section */}
      <div className="w-full max-w-[342px] mt-3 h-6">
        {errorMsg && (
          <p className="text-xs text-[#FA5151] text-center font-medium animate-pulse">
            {errorMsg}
          </p>
        )}
      </div>

      <div className="w-full max-w-[342px] mt-6 flex flex-col gap-4 items-center">
        <button
          type="submit"
          onClick={handleAuthSubmit}
          disabled={isLoading}
          className="w-full h-12 bg-[#07c160] hover:bg-opacity-90 active:bg-opacity-80 transition-all duration-150 rounded-lg flex items-center justify-center text-white text-[17px] font-medium shadow-sm cursor-pointer disabled:opacity-50"
        >
          {isLoading ? (
            <div className="flex items-center gap-2">
              <Loader2 className="w-5 h-5 animate-spin" />
              <span>Please wait...</span>
            </div>
          ) : (
            <span>{isLoginMode ? 'Log In' : 'Register'}</span>
          )}
        </button>

        <button
          onClick={toggleAuthMode}
          type="button"
          className="text-[17px] font-medium text-[#006d33] hover:opacity-80 transition-opacity cursor-pointer mt-2"
        >
          {isLoginMode ? 'Create account' : 'Back to login'}
        </button>
      </div>

      {/* Bottom Footer agreements */}
      <div className="absolute bottom-12 w-full text-center px-4">
        <p className="text-[14px] text-[#808080] leading-relaxed">
          By continuing, you agree to our Terms of<br />Service.
        </p>
      </div>
    </div>
  );
}

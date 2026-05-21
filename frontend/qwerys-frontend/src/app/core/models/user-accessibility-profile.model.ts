export interface UserAccessibilityProfile {
  blindMode: boolean;
  lowVisionMode: boolean;
  dyslexiaMode: boolean;
  deafMode: boolean;
  adhdMode: boolean;
  studentMode: boolean;
  expertMode: boolean;
  highContrast: boolean; // Alto contraste — activado desde el Panel de Accesibilidad (Día 22)
  deviceType: 'mobile' | 'tablet' | 'laptop' | 'desktop';
  language: 'es' | 'en';
  darkTheme: boolean;
}

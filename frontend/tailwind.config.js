import forms from '@tailwindcss/forms';
import containerQueries from '@tailwindcss/container-queries';

// La mateixa configuració que abans vivia dins d'index.html per al CDN de
// Tailwind. Ara el CSS es genera amb "npm run build:assets" i es desa a
// public/css/tailwind.css.
//
// Tailwind només genera les classes que troba escrites senceres als fitxers
// de "content". Per això no es poden construir en temps d'execució
// (`bg-${color}-500`): ja estava prohibit, i ara a més no sortirien al CSS.
export default {
    content: ['./public/index.html', './public/js/**/*.js'],
    darkMode: 'class',
    theme: {
        extend: {
            colors: {
                primary: '#1152d4',
                'background-light': '#f6f6f8',
                'background-dark': '#101622',
            },
            fontFamily: {
                display: ['"Inter Variable"', 'Inter', 'sans-serif'],
            },
            borderRadius: { DEFAULT: '0.5rem', lg: '1rem', xl: '1.5rem', full: '9999px' },
        },
    },
    plugins: [forms, containerQueries],
};

module.exports = {
      darkMode: 'class',
      theme: {
        extend: {
          colors: {
            brand: {
              50: '#EEF2FF',
              100: '#E0E7FF',
              200: '#C7D2FE',
              300: '#A5B4FC',
              400: '#818CF8',
              500: '#6366F1',
              600: '#4F46E5', /* 安卓原生主色 Indigo40 */
              700: '#4338CA',
              800: '#3730A3',
              900: '#312E81',
            },
            cyan: {
              50: '#F0F9FF',
              100: '#E0F2FE',
              200: '#BAE6FD',
              400: '#38BDF8',
              500: '#0EA5E9',
              600: '#0284C7', /* 安卓原生辅色 Cyan40 */
              700: '#0369A1',
            },
            m3: {
              surface: '#F8FAFC',
              card: '#FFFFFF',
              primary: '#4F46E5',
              secondary: '#0284C7',
              accent: '#E0E7FF'
            }
          }
        }
      }
    };
module.exports.content = ["./web/index.html", "./web/static/*.js"];

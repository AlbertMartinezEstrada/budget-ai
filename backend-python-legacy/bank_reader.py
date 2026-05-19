import pandas as pd

def netejar_banc(file_path):
    try:
        # 1. Llegim el CSV
        df = pd.read_csv(file_path, sep=';')
        
        # 2. Netegem l'import de forma segura:
        #    a. Treiem 'EUR'
        #    b. Treiem els punts dels milers (el '.' que et donava l'error)
        #    c. Cambiem la coma decimal ',' per un punt '.'
        df['Importe_Net'] = (
            df['Importe']
            .str.replace('EUR', '', regex=False)
            .str.replace('.', '', regex=False)  # Treiem el punt dels milers (1.301 -> 1301)
            .str.replace(',', '.', regex=False) # Canviem la coma decimal (1301,58 -> 1301.58)
            .astype(float)
        )
        
        # 3. Filtrem només els gastos (imports negatius)
        gastos = df[df['Importe_Net'] < 0].copy()
        
        # 4. Convertim a positiu per guardar-lo a l'Excel
        gastos['Cost'] = gastos['Importe_Net'].abs()
        
        resultat = gastos[['Concepto', 'Fecha', 'Cost']].rename(columns={
            'Concepto': 'concepte_original',
            'Fecha': 'data'
        })
        
        return resultat
    except Exception as e:
        print(f"Error detallat a bank_reader: {e}")
        return None
import google.generativeai as genai
import json
import pandas as pd

# 1. Configura la teva clau aquí
API_KEY = "xxxxx"
genai.configure(api_key=API_KEY)

def classificar_gastos_amb_ia(df_gastos):
    """
    Recibe un DataFrame con columnas: concepte_original, data, Cost
    Devuelve un DataFrame con: companyName, category, description_curta, cost, date, concepte_original
    """
    if df_gastos is None or len(df_gastos) == 0:
        print("Error: DataFrame buit")
        return None

    # Preparem el model
    model = genai.GenerativeModel('gemini-2.5-flash')
    
    # Definim les categories (les mateixes que tens al Readme)
    categories_text = """
    1. Menjar i supermercat, 2. Bars i restaurants, 3. Transport, 4. Allotjament, 
    5. Compres i roba, 6. Higiene i bellesa, 7. Salut i farmàcia, 8. Gimnàs i esport, 
    9. Cultura, oci i entreteniment, 10. Jocs de taula i videojocs, 11. Festa i alcohol, 
    12. Tecnologia, 13. Regals i detalls, 14. Casa i mobiliari, 15. Mascotes, 
    16. Altres, 17. Educació, 18. Inversions.
    """

    # Convertim els gastos a un format de text simple per a la IA
    llista_per_ia = df_gastos[['concepte_original', 'data', 'Cost']].to_string(index=False)

    prompt = f"""
    Ets un assistent financer. Classifica aquests moviments bancaris segons aquestes categories:
    {categories_text}

    MOVIMENTS A CLASSIFICAR:
    {llista_per_ia}

    INSTRUCCIONS:
    - Respon EXCLUSIVAMENT en format JSON (una llista d'objectes).
    - Camps obligatoris: "companyName" (nom net de l'empresa), "category" (una de les 18 categories), "description_curta" (descripció breu), "cost" (import numèric), "date" (data original).
    - Si dubtes, marca el camp "dubte": true.
    - NO afegeixis text fora del JSON.
    """

    try:
        response = model.generate_content(prompt)

        # Intentem netejar la resposta (a vegades la IA posa ```json ... ```)
        text_neteja = response.text.replace('```json', '').replace('```', '').strip()

        resultats_json = json.loads(text_neteja)
        df_resultat = pd.DataFrame(resultats_json)

        # Afegir la columna concepte_original des del DataFrame original
        if 'concepte_original' not in df_resultat.columns and 'concepte_original' in df_gastos.columns:
            df_resultat['concepte_original'] = df_gastos['concepte_original'].values[:len(df_resultat)]

        return df_resultat

    except json.JSONDecodeError as e:
        print(f"Error parsejant el JSON de la IA: {e}")
        print(f"Resposta rebuda: {response.text[:500]}")
        return None
    except Exception as e:
        print(f"Error inesperat: {e}")
        return None

# Prova d'integració ràpida
if __name__ == "__main__":
    from bank_reader import netejar_banc
    
    # 1. Llegim banc
    df_inicial = netejar_banc('extractDocument_20251213.csv')
    
    # 2. Classifiquem (agafem només els 5 primers per provar)
    print("Classificant amb la IA...")
    df_classificat = classificar_gastos_amb_ia(df_inicial.head(5))
    
    if df_classificat is not None:
        print("\n--- RESULTAT DE LA IA ---")
        print(df_classificat[['companyName', 'category', 'cost']])
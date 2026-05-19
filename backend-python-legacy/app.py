import streamlit as st
import pandas as pd
from bank_reader import netejar_banc
from ai_engine import classificar_gastos_amb_ia
from excel_writer import guardar_a_excel

st.set_page_config(page_title="Gestor de Gastos IA", layout="wide")

st.title("💰 Gestor de Gastos Inteligente")
st.write("Sube el extracto de tu banco y deja que la IA organice tu Excel.")

# 1. Subida de archivo
uploaded_file = st.file_uploader("Sube tu CSV del banco", type=["csv"])

if uploaded_file is not None:
    # Guardamos temporalmente para que los otros módulos lo lean
    with open("temp_banco.csv", "wb") as f:
        f.write(uploaded_file.getbuffer())
    
    # Procesamiento inicial
    df_net = netejar_banc("temp_banco.csv")
    st.success(f"Se han detectado {len(df_net)} gastos.")
    
    # 2. Botón para llamar a la IA
    if st.button("🤖 Clasificar con IA"):
        with st.spinner("La IA está analizando tus gastos..."):
            df_final = classificar_gastos_amb_ia(df_net)
            
            if df_final is not None:
                st.session_state['df_final'] = df_final
    
    # 3. Mostrar resultados y edición
    if 'df_final' in st.session_state:
        st.subheader("Revisa y edita los datos:")
        # Permitimos que edites la tabla directamente en la web
        edited_df = st.data_editor(st.session_state['df_final'], num_rows="dynamic")
        
        # 4. Botón final para guardar en el Excel real
        if st.button("🚀 Guardar en Presupost 2026.xlsx"):
            try:
                guardar_a_excel(edited_df, "Presupost 2026.xlsx")
                st.balloons()
                st.success("¡Todo guardado correctamente en tu Excel!")
            except Exception as e:
                st.error(f"Error al guardar: {e}")